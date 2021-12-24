package com.mybank.payments.batches.integration;

import com.mybank.payments.batches.integration.executor.BatchOrderExecutor;
import com.mybank.payments.batches.integration.executor.ProcessedResultsExecutor;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExampleConfiguration {

    @Bean("selectedBatchOrderExecutor")
    BatchOrderExecutor batchOrderExecutor(@Value("${service.activeExample}") String activeExampleProperty,
                                            List<BatchOrderExecutor> batchOrderExecutors) {
        ExampleMode activeExample = ExampleMode.from(activeExampleProperty);
        return batchOrderExecutors.stream()
            .filter(executor -> executor.getType().equals(activeExample))
            .findAny()
            .orElseThrow(() -> new RuntimeException("Batch order executor not found for selected example: " + activeExampleProperty));
    }

    @Bean("selectedProcessedResultsExecutor")
    ProcessedResultsExecutor processedResultsExecutor(@Value("${service.activeExample}") String activeExampleProperty,
                                                        List<ProcessedResultsExecutor> processedResultsExecutors) {
        ExampleMode activeExample = ExampleMode.from(activeExampleProperty);
        return processedResultsExecutors.stream()
            .filter(executor -> executor.getType().equals(activeExample))
            .findAny()
            .orElse(null);
    }

}
