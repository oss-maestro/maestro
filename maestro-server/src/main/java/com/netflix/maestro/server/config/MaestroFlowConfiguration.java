package com.netflix.maestro.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.maestro.engine.dao.MaestroStepInstanceDao;
import com.netflix.maestro.engine.dao.MaestroWorkflowInstanceDao;
import com.netflix.maestro.engine.handlers.MaestroExecutionPreparer;
import com.netflix.maestro.engine.listeners.MaestroFinalFlowStatusCallback;
import com.netflix.maestro.engine.publisher.MaestroJobEventPublisher;
import com.netflix.maestro.engine.tasks.MaestroEndTask;
import com.netflix.maestro.engine.tasks.MaestroGateTask;
import com.netflix.maestro.engine.tasks.MaestroStartTask;
import com.netflix.maestro.engine.tasks.MaestroTask;
import com.netflix.maestro.engine.transformation.WorkflowTranslator;
import com.netflix.maestro.engine.utils.RollupAggregationHelper;
import com.netflix.maestro.engine.utils.WorkflowHelper;
import com.netflix.maestro.flow.dao.MaestroFlowDao;
import com.netflix.maestro.flow.engine.ExecutionContext;
import com.netflix.maestro.flow.engine.FlowExecutor;
import com.netflix.maestro.flow.runtime.ExecutionPreparer;
import com.netflix.maestro.flow.runtime.FinalFlowStatusCallback;
import com.netflix.maestro.metrics.MaestroMetrics;
import com.netflix.maestro.models.Constants;
import com.netflix.maestro.models.definition.StepType;
import com.netflix.maestro.server.properties.MaestroEngineProperties;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * beans for maestro flow related classes.
 *
 * @author jun-he
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MaestroEngineProperties.class)
public class MaestroFlowConfiguration {
  @Bean
  public FinalFlowStatusCallback finalFlowStatusCallback(
      MaestroTask maestroTask,
      MaestroWorkflowInstanceDao instanceDao,
      MaestroStepInstanceDao stepInstanceDao,
      MaestroJobEventPublisher publisher,
      @Qualifier(Constants.MAESTRO_QUALIFIER) ObjectMapper objectMapper,
      MaestroMetrics metrics) {
    LOG.info("Creating maestro finalFlowStatusCallback within Spring boot...");
    return new MaestroFinalFlowStatusCallback(
        maestroTask, instanceDao, stepInstanceDao, publisher, objectMapper, metrics);
  }

  @Bean
  public ExecutionPreparer executionPreparer(
      @Qualifier(Constants.MAESTRO_QUALIFIER) ObjectMapper objectMapper,
      MaestroWorkflowInstanceDao instanceDao,
      MaestroStepInstanceDao stepInstanceDao,
      WorkflowTranslator translator,
      WorkflowHelper workflowHelper,
      RollupAggregationHelper rollupAggregationHelper) {
    LOG.info("Creating maestro executionPreparer within Spring boot...");
    return new MaestroExecutionPreparer(
        instanceDao,
        stepInstanceDao,
        translator,
        workflowHelper,
        rollupAggregationHelper,
        objectMapper);
  }

  @Bean(initMethod = "init", destroyMethod = "shutdown")
  public FlowExecutor flowExecutor(ExecutionContext executionContext) {
    LOG.info("Creating maestro flowExecutor within Spring boot...");
    return new FlowExecutor(executionContext);
  }

  @Bean
  public ExecutionContext executionContext(
      ExecutionPreparer executionPreparer,
      MaestroFlowDao flowDao,
      MaestroTask maestroTask,
      MaestroStartTask startTask,
      MaestroEndTask endTask,
      MaestroGateTask gateTask,
      FinalFlowStatusCallback finalCallback,
      MaestroEngineProperties properties,
      MaestroMetrics metrics) {
    LOG.info("Creating maestro executionContext within Spring boot...");
    return new ExecutionContext(
        Map.of(
            Constants.MAESTRO_TASK_NAME,
            maestroTask,
            Constants.DEFAULT_START_TASK_NAME,
            startTask,
            Constants.DEFAULT_END_TASK_NAME,
            endTask,
            StepType.JOIN.name(),
            gateTask),
        finalCallback,
        executionPreparer,
        flowDao,
        properties,
        metrics);
  }
}
