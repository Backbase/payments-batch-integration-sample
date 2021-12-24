package com.mybank.payments.batches.integration.executor.nachafile;

import static com.backbase.batches.nacha.statusmanager.BatchStatusManager.BatchExpectedStatus.ACCEPTED;
import static java.util.Optional.ofNullable;

import com.backbase.batches.nacha.model.result.BankResult;
import com.backbase.batches.nacha.model.result.BankResult.BankBatchOrderResult;
import com.backbase.batches.nacha.model.result.HandlerResult;
import com.backbase.batches.nacha.model.result.HandlerResult.HandlerBatchOrderResult;
import com.backbase.batches.nacha.model.result.HandlerResult.HandlerPaymentItemResult;
import com.backbase.batches.nacha.model.result.StatusInfo;
import com.backbase.batches.nacha.statusmanager.BatchStatusManager;
import com.mybank.payments.batches.integration.executor.ProcessedResultsExecutor;
import com.mybank.payments.batches.integration.ExampleMode;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProcessedNachaFilesResultsExecutor implements ProcessedResultsExecutor {

    @Getter
    private final Queue<Pair<String, BankResult>> processedResultsPerFileQueue;

    private final BatchStatusManager batchStatusManager;

    private final Map<String, HandlerResult> nachaHandlerResultsPersistenceStore = new HashMap<>();

    public void saveNachaHandlerResult(HandlerResult handlerResult) {
        log.debug("Saved result for fileId {} after NachaHandler generated nacha file: {}",
            handlerResult.getFileInfo().getFileReferenceCode(), handlerResult);
        nachaHandlerResultsPersistenceStore.put(handlerResult.getFileInfo().getFileReferenceCode(), handlerResult);
    }

    @Override
    public void execute() {
        while(!processedResultsPerFileQueue.isEmpty()) {
            Pair<String, BankResult> processedResult = processedResultsPerFileQueue.poll();
            log.debug("Polled proccessed nacha file result {} for file reference {}", processedResult.getValue(), processedResult.getKey());
            HandlerResult nachaHandlerResult = ofNullable(nachaHandlerResultsPersistenceStore.remove(processedResult.getKey()))
                .orElseThrow(() -> new RuntimeException("Nacha handler result not found for file " + processedResult.getKey()));
            log.debug("Found result saved by NachaHandler after generating: {}", nachaHandlerResult);
            notifyBatchService(processedResult.getValue(), nachaHandlerResult);
        }
    }

    private void notifyBatchService(BankResult bankResult, HandlerResult nachaHandlerResult) {
        bankResult.getBatches().forEach((batchNachaId, bankBatchResult) -> {
            HandlerBatchOrderResult handlerBatchResult = findHandlerBatch(nachaHandlerResult, batchNachaId);
            log.debug("Marking batch result: {}", bankBatchResult);
            markBatch(handlerBatchResult, bankBatchResult);
            if (!bankBatchResult.isRejected()) {
                log.debug("Marking rejected payment result: {}", bankBatchResult);
                markRejectedPayments(bankBatchResult, handlerBatchResult);
            }
        });
    }

    private void markBatch(HandlerBatchOrderResult handlerBatchResult, BankBatchOrderResult bankBatchResult) {
        if (bankBatchResult.isRejected()) {
            batchStatusManager.rejectBatch(ACCEPTED, handlerBatchResult.getBatchOrderId(), bankBatchResult.getStatusInfo());
        } else {
            batchStatusManager.markBatchProcessed(handlerBatchResult.getBatchOrderId(), bankBatchResult.getStatusInfo());
        }
    }

    private void markRejectedPayments(BankBatchOrderResult acceptedBatch, HandlerBatchOrderResult handlerBatchResult) {
        Map<String, StatusInfo> rejectedPaymentsStatuses = acceptedBatch.getEntries().entrySet().stream()
            .filter(paymentEntry -> paymentEntry.getValue().isRejected())
            .collect(Collectors.toMap(
                entry -> {
                    HandlerPaymentItemResult handlerPaymentResult =
                        findHandlerPayment(handlerBatchResult, entry.getValue().getTraceNumber());
                    return handlerPaymentResult.getPaymentItemId();
                },
                entry ->
                    ofNullable(entry.getValue().getStatusInfo())
                        .orElse(StatusInfo.builder().rejected(Boolean.TRUE).build())
                ));
        batchStatusManager.rejectPaymentItems(ACCEPTED, handlerBatchResult.getBatchOrderId(), rejectedPaymentsStatuses);
    }

    private HandlerBatchOrderResult findHandlerBatch(HandlerResult nachaHandlerResult, String batchNachaId) {
        return ofNullable(
            nachaHandlerResult.getBatches().get(batchNachaId))
            .orElseThrow(() -> new RuntimeException(
                String.format("Batch %s not found in nacha handler result.", batchNachaId)));
    }

    private HandlerPaymentItemResult findHandlerPayment(HandlerBatchOrderResult handlerBatchResult, String paymentNachaId) {
        return ofNullable(handlerBatchResult.getPaymentItems().get(paymentNachaId))
            .orElseThrow(() -> new RuntimeException("Mappings not found for payment "
                + paymentNachaId + " at batch " + handlerBatchResult.getBatchOrderId()));
    }

    @Override
    public ExampleMode getType() {
        return ExampleMode.NACHA_FILE;
    }
}
