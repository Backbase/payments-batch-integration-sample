package com.mybank.payments.batches.integration;

import static java.util.Optional.ofNullable;

import com.mybank.payments.batches.integration.executor.BatchOrderExecutor;
import com.mybank.payments.batches.integration.executor.Executor;
import com.mybank.payments.batches.integration.executor.ProcessedResultsExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ExampleManager {

    private final BatchOrderExecutor batchOrderExecutor;
    private final ProcessedResultsExecutor processedResultsExecutor;

    public ExampleManager(
        @Qualifier("selectedBatchOrderExecutor") BatchOrderExecutor batchOrderExecutor,
        @Qualifier("selectedProcessedResultsExecutor") ObjectProvider<ProcessedResultsExecutor> processedResultsExecutorProvider) {
        this.batchOrderExecutor = batchOrderExecutor;
        this.processedResultsExecutor = processedResultsExecutorProvider.getIfAvailable();
    }

    /**
     * Scheduled method, periodically picking up all the batch orders from the queue created by the BatchOrderController
     * then processing it according to activeExample strategy.
     */
    @Scheduled(fixedRateString = "${service.batchOrderExecutor.fixedRate}", initialDelay = 10000)
    public void execute() {
        batchOrderExecutor.execute();
        ofNullable(processedResultsExecutor).ifPresent(Executor::execute);
    }

}
