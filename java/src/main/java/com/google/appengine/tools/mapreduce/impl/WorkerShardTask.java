// Copyright 2012 Google Inc. All Rights Reserved.

package com.google.appengine.tools.mapreduce.impl;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.appengine.tools.mapreduce.InputReader;
import com.google.appengine.tools.mapreduce.OutputWriter;
import com.google.appengine.tools.mapreduce.Worker;
import com.google.appengine.tools.mapreduce.WorkerContext;
import com.google.appengine.tools.mapreduce.impl.handlers.MemoryLimiter;
import com.google.appengine.tools.mapreduce.impl.shardedjob.JobFailureException;
import com.google.appengine.tools.mapreduce.impl.shardedjob.RecoverableException;
import com.google.appengine.tools.mapreduce.impl.shardedjob.ShardFailureException;
import com.google.common.base.Stopwatch;

import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base class for the specific MR stage workers.
 * Each subclass is responsible to set the context on its input, output, and worker.
 *
 * @author ohler@google.com (Christian Ohler)
 *
 * @param <I> type of input values consumed by the worker
 * @param <O> type of output values produced by the worker
 * @param <C> type of context required by the worker
 */
public abstract class WorkerShardTask<I, O, C extends WorkerContext<O>> implements
    IncrementalTaskWithContext {

  private static final Logger log = Logger.getLogger(WorkerShardTask.class.getName());
  private static final long serialVersionUID = 992552712402490981L;
  protected static final MemoryLimiter LIMITER = new MemoryLimiter();

  private transient Stopwatch overallStopwatch;
  private transient Stopwatch inputStopwatch;
  private transient Stopwatch workerStopwatch;
  protected transient Long claimedMemory; // Assigned in prepare

  private final IncrementalTaskContext context;
  private boolean inputExhausted = false;
  private boolean isFirstSlice = true;
  private boolean wasFinalized;

  protected WorkerShardTask(IncrementalTaskContext context) {
    this.context = context;
  }

  @Override
  public final IncrementalTaskContext getContext() {
    return context;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[context=" + context + ", inputExhausted="
        + inputExhausted + ", isFirstSlice=" + isFirstSlice + "]";
  }

  @Override
  public void cleanup() {
    if (claimedMemory != null) {
      LIMITER.release(claimedMemory);
    }
  }

  @Override
  public void prepare() {
    claimedMemory = LIMITER.claim(estimateMemoryRequirement() / 1024 / 1024);
  }

  @Override
  public void run() {
    try {
      beginSlice();
    } catch (JobFailureException | RecoverableException | ShardFailureException ex) {
      throw ex;
    } catch (IOException ex) {
      throw new RuntimeException("IOException during beginSlice", ex);
    }
    overallStopwatch = Stopwatch.createStarted();
    inputStopwatch =  Stopwatch.createUnstarted();
    workerStopwatch = Stopwatch.createUnstarted();

    int workerCalls = 0;
    int itemsRead = 0;

    I next = null;
    try {
      do {
        inputStopwatch.start();
        try {
          next = getInputReader().next();
          itemsRead++;
        } catch (NoSuchElementException e) {
          inputExhausted = true;
          break;
        } catch (IOException e) {
          if (workerCalls == 0) {
            // No progress, lets retry the slice
            throw new RecoverableException("Failed on first input", e);
          }
          // We made progress, persist it.
          log.log(Level.WARNING, "An IOException while reading input, ending slice early", e);
          break;
        }
        inputStopwatch.stop();

        workerCalls++;
        // TODO(ohler): workerStopwatch includes time spent in emit() and the
        // OutputWriter, which is very significant because it includes I/O. I
        // think a clean solution would be to have the context measure the
        // OutputWriter's time, and to subtract that from the time measured by
        // workerStopwatch.
        workerStopwatch.start();
        // TODO(user): add a way to check if the writer is OK with a slice-retry
        // and if so, wrap callWorker with try~catch and propagate as RecoverableException.
        // Otherwise should be propagated as ShardFailureException and remove the
        // individuals try~catch in the callWorker implementations.
        callWorker(next);
        workerStopwatch.stop();
      } while (!shouldCheckpoint(overallStopwatch.elapsed(MILLISECONDS)));
    } finally {
      log.info("Ending slice after " + itemsRead + " items read and calling the worker "
          + workerCalls + " times");
    }
    overallStopwatch.stop();
    log.info("Ending slice, inputExhausted=" + inputExhausted + ", overallStopwatch="
        + overallStopwatch + ", workerStopwatch=" + workerStopwatch + ", inputStopwatch="
        + inputStopwatch);

    context.incrementWorkerCalls(workerCalls);
    context.incrementWorkerMillis(workerStopwatch.elapsed(MILLISECONDS));
    try {
      endSlice(inputExhausted);
    } catch (IOException ex) {
      throw new RuntimeException("IOException during endSlice", ex);
    }
    context.setLastWorkItemString(formatLastWorkItem(next));
  }

  private void beginSlice() throws IOException {
    if (isFirstSlice) {
      getOutputWriter().beginShard();
      getInputReader().beginShard();
    }
    getInputReader().beginSlice();
    getOutputWriter().beginSlice();
    if (isFirstSlice) {
      getWorker().beginShard();
    }
    getWorker().beginSlice();
    isFirstSlice = false;
  }

  private void endSlice(boolean inputExhausted) throws IOException {
    getWorker().endSlice();
    if (inputExhausted) {
      getWorker().endShard();
    }
    getOutputWriter().endSlice();
    getInputReader().endSlice();
    if (inputExhausted) {
      getOutputWriter().endShard();
      try {
        getInputReader().endShard();
      } catch (IOException ex) {
        // Ignore - retrying a slice or shard will not fix that
      }
    }
  }

  protected static String abbrev(Object x) {
    if (x == null) {
      return null;
    }
    String s = x.toString();
    if (s.length() > MapReduceConstants.MAX_LAST_ITEM_STRING_SIZE) {
      return s.substring(0, MapReduceConstants.MAX_LAST_ITEM_STRING_SIZE) + "...";
    } else {
      return s;
    }
  }

  @Override
  public boolean isDone() {
    return inputExhausted;
  }


  protected final void setFinalized() {
    wasFinalized = true;
  }

  protected final boolean wasFinalized() {
    return wasFinalized;
  }

  /**
   * @return true iff a checkpoint should be performed.
   */
  protected abstract boolean shouldCheckpoint(long timeElapsed);
  protected abstract long estimateMemoryRequirement();
  protected abstract Worker<C> getWorker();
  protected abstract InputReader<I> getInputReader();
  protected abstract void callWorker(I input);
  protected abstract String formatLastWorkItem(I item);
  public abstract OutputWriter<O> getOutputWriter();
}
