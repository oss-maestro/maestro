package com.netflix.maestro.flow.engine;

import com.netflix.maestro.exceptions.MaestroInternalError;
import com.netflix.maestro.exceptions.MaestroNotFoundException;
import com.netflix.maestro.exceptions.MaestroRetryableError;
import com.netflix.maestro.exceptions.MaestroUnprocessableEntityException;
import com.netflix.maestro.flow.Constants;
import com.netflix.maestro.flow.dao.MaestroFlowDao;
import com.netflix.maestro.flow.models.Flow;
import com.netflix.maestro.flow.models.FlowGroup;
import com.netflix.maestro.flow.models.Task;
import com.netflix.maestro.flow.properties.FlowEngineProperties;
import com.netflix.maestro.flow.runtime.ExecutionPreparer;
import com.netflix.maestro.flow.runtime.FinalFlowStatusCallback;
import com.netflix.maestro.flow.runtime.FlowTask;
import com.netflix.maestro.flow.utils.ExecutionHelper;
import com.netflix.maestro.metrics.MaestroMetrics;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared execution context. It is thread safe and is expected to be called from other threads or
 * virtual threads. It is expected to be a singleton.
 *
 * @author jun-he
 */
@Slf4j
public class ExecutionContext {
  // central dispatcher to schedule a delayed action for all actors
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  // virtual thread executor service to create one virtual thread per actor
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  // shared among all actors
  private final Map<String, FlowTask> flowTaskMap;
  private final FinalFlowStatusCallback finalCallback;
  private final ExecutionPreparer executionPreparer;
  private final MaestroFlowDao flowDao;
  private final ExecutorService internalWorkers; // avoid virtual thread running business logic

  @Getter private final FlowEngineProperties properties;
  @Getter private final MaestroMetrics metrics;

  /** Constructor. */
  public ExecutionContext(
      Map<String, FlowTask> flowTaskMap,
      FinalFlowStatusCallback finalCallback,
      ExecutionPreparer executionPreparer,
      MaestroFlowDao flowDao,
      FlowEngineProperties properties,
      MaestroMetrics metrics) {
    this.flowTaskMap = flowTaskMap;
    this.finalCallback = finalCallback;
    this.executionPreparer = executionPreparer;
    this.flowDao = flowDao;
    this.internalWorkers = Executors.newFixedThreadPool(properties.getInternalWorkerNumber());
    this.properties = properties;
    this.metrics = metrics;
  }

  /** Run an actor. */
  public void run(Runnable runnable) {
    executor.execute(runnable);
  }

  /**
   * Schedule an action for the future.
   *
   * @param action action to schedule
   * @param delayInMillis delay to wait
   * @return the future object
   */
  public ScheduledFuture<?> schedule(Runnable action, long delayInMillis) {
    return scheduler.schedule(action, delayInMillis, TimeUnit.MILLISECONDS);
  }

  /** Gracefully shutdown the execution context. */
  public void shutdown() {
    LOG.info("ExecutionContext shutdown is started");
    ExecutionHelper.shutdown(executor, "ExecutionContext executor");
    ExecutionHelper.shutdown(scheduler, "ExecutionContext scheduler");
    LOG.info("ExecutionContext shutdown is completed");
  }

  /** Run business logic using internal workers instead of directly over virtual threads. */
  private <T> T runInternally(Callable<T> callable, Flow flow, String taskRef, String method) {
    var future = internalWorkers.submit(callable);
    try {
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MaestroRetryableError(
          e,
          "flow task %s[%s] is interrupted for method [%s] due to [%s], retry it",
          method,
          flow.getReference(),
          taskRef,
          e.getMessage());
    } catch (ExecutionException | CancellationException e) {
      throw new MaestroRetryableError(
          e,
          "flow task %s[%s] is failed for method [%s] due to [%s], retry it",
          method,
          flow.getReference(),
          taskRef,
          e.getMessage());
    }
  }

  /**
   * Run prepare task before running any maestro provided user tasks. It will retry prepare failures
   * forever as this is unexpected (i.e. bugs). Also, it passes reasonForIncomplete to handle a
   * special case.
   *
   * @param flow maestro flow
   * @throws MaestroRetryableError notify callers to retry
   */
  public void prepare(Flow flow) {
    try {
      Task prepare = flow.getPrepareTask();
      prepare.setStartTime(System.currentTimeMillis());
      runInternally(
          () -> flowTaskMap.get(prepare.getTaskType()).execute(flow, prepare),
          flow,
          "preparer",
          "prepare");
      if (!prepare.getStatus().isTerminal()) {
        LOG.info("prepare task for flow {} is not done yet, will retry", flow.getReference());
        throw new MaestroRetryableError("prepare task is not done yet, will retry");
      } else {
        flow.setReasonForIncompletion(prepare.getReasonForIncompletion());
        flow.markUpdate();
      }
    } catch (MaestroRetryableError mre) {
      throw mre;
    } catch (RuntimeException e) {
      LOG.warn("prepare task in flow {} throws an error, will retry it", flow.getReference(), e);
      throw new MaestroRetryableError(e, "retry prepare task due to an exception");
    }
  }

  /** Run monitoring task. */
  public void refresh(Flow flow) {
    Task monitor = flow.getMonitorTask();
    // safe to access tasks directly in the execution as the monitor task is run within the flow
    runInternally(
        () -> flowTaskMap.get(monitor.getTaskType()).execute(flow, monitor),
        flow,
        "monitor",
        "refresh");
    flow.markUpdate();
  }

  /** Run the flow's final callback function. */
  public void finalCall(Flow flow) {
    if (flow.getFlowDef().isFinalFlowStatusCallbackEnabled()) {
      runInternally(
          () -> {
            if (flow.getStatus().isSuccessful()) {
              finalCallback.onFlowCompleted(flow);
            } else {
              finalCallback.onFlowTerminated(flow);
            }
            finalCallback.onFlowFinalized(flow);
            return null;
          },
          flow,
          "finalCallback",
          "finalCall");
    }
  }

  /** run the one-time start logic of a task. */
  public void start(Flow flow, Task task) {
    runInternally(
        () -> {
          flowTaskMap.get(task.getTaskType()).start(flow, task);
          return null;
        },
        flow,
        task.referenceTaskName(),
        "start");
  }

  /** run the repeated execute logic of a task. */
  public boolean execute(Flow flow, Task task) {
    return runInternally(
        () -> flowTaskMap.get(task.getTaskType()).execute(flow, task),
        flow,
        task.referenceTaskName(),
        "execute");
  }

  /** Cancel the task. */
  public void cancel(Flow flow, Task task) {
    runInternally(
        () -> {
          flowTaskMap.get(task.getTaskType()).cancel(flow, task);
          return null;
        },
        flow,
        task.referenceTaskName(),
        "cancel");
  }

  /** Clone the task by making a deep copy. */
  public Task cloneTask(Task task) {
    try {
      return executionPreparer.cloneTask(task);
    } catch (RuntimeException e) {
      throw new MaestroUnprocessableEntityException(
          "cannot clone task: [%s] due to error [%s]", task.referenceTaskName(), e.getMessage());
    }
  }

  /**
   * Save a flow to DB. It might fail due to duplicate or conflict and retry will fix it. Flow DB
   * only saves the in-processing flows. So its primary key might not protect all race conditions.
   * It's caller's responsibility to retry and to take care of those.
   *
   * @param flow flow to persist
   * @throws MaestroRetryableError notify callers to retry
   */
  public void saveFlow(Flow flow) {
    try {
      flowDao.insertFlow(flow);
    } catch (MaestroInternalError e) {
      throw new MaestroRetryableError(
          e, "insertFlow is failed for %s and please retry", flow.getReference());
    }
  }

  /**
   * Delete a flow.
   *
   * @param flow flow to delete
   */
  public void deleteFlow(Flow flow) {
    flowDao.deleteFlow(flow);
  }

  /**
   * fetch a batch of flows using flow dao.
   *
   * @param group flow group to load flows
   * @param limit maximum number of flows to load
   * @param idCursor flow id cursor to start to load
   * @return a list of flows
   */
  public List<Flow> getFlowsFrom(FlowGroup group, long limit, String idCursor) {
    try {
      return flowDao.getFlows(group, limit, idCursor);
    } catch (MaestroInternalError e) {
      // if fail in the middle, we need to retry the last action
      return null; // indicate error
    }
  }

  /**
   * Resume a maestro flow by filling data into it.
   *
   * @param flow the flow object to be filled with tasks, etc.
   */
  public void resumeFlow(Flow flow) {
    try {
      boolean flag = executionPreparer.resume(flow);
      if (flag) {
        prepare(flow);
      }
    } catch (MaestroNotFoundException nfe) {
      LOG.info("cannot find the reference flow: {}. Ignore it.", flow.getReference(), nfe);
    } catch (RuntimeException e) {
      LOG.warn("got an exception for resuming flow for {} and will retry", flow.getReference(), e);
      throw new MaestroRetryableError(e, "retry resuming flow due to an exception");
    }
  }

  /**
   * Save a flow group to DB. It might fail due to duplicate or conflict and retry will fix it. It's
   * caller's responsibility to retry and find the right node who owns the group or if no one owns
   * it, try it again.
   *
   * @param groupId new flow group id
   * @param address new flow group owner address
   * @return the added flow group
   * @throws MaestroRetryableError error to retry
   */
  public FlowGroup trySaveGroup(long groupId, String address) {
    if (executor.isShutdown() || scheduler.isShutdown()) {
      throw new MaestroRetryableError(
          "ExecutionContext is shutdown and cannot save a group-[%s] and please retry.", groupId);
    }
    try {
      return flowDao.insertGroup(groupId, address);
    } catch (MaestroInternalError e) {
      throw new MaestroRetryableError(
          e, "insertGroup is failed for group-[%s] and please retry", groupId);
    }
  }

  /**
   * Get an unclaimed flow group and then claim it. Note that each flow actor will resume to load
   * data from maestro workflow and step instances to rebuild flow back.
   */
  public FlowGroup claimGroup() {
    return flowDao.claimExpiredGroup(
        properties.getEngineAddress(), properties.getExpirationDurationInMillis());
  }

  /**
   * It does heartbeat plus validating ownership. If losing ownership, it means this JVM is in
   * trouble and let's brutally kill it to reduce the damage. If valid, it returns the valid until
   * timestamp, which is the heartbeat timestamp plus the expiration duration.
   *
   * @param group flow group to heartbeat
   */
  public long heartbeatGroup(FlowGroup group) {
    Long heartbeatTs = flowDao.heartbeatGroup(group);
    if (heartbeatTs == null) {
      LOG.error("heartbeat detects an invalid ownership for [{}]. Kill JVM to reconcile.", group);
      Runtime.getRuntime().halt(Constants.INVALID_OWNERSHIP_EXIT_CODE);
    }
    return heartbeatTs + properties.getExpirationDurationInMillis();
  }

  public void releaseGroup(FlowGroup group) {
    LOG.info("Release the flow group: [{}]", group);
    flowDao.releaseGroup(group);
  }
}
