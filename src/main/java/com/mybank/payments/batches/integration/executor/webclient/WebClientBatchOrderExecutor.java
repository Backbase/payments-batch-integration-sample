package com.mybank.payments.batches.integration.executor.webclient;

import com.mybank.payments.batches.integration.executor.BatchOrderExecutor;
import com.mybank.payments.batches.integration.ExampleMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.backbase.payments.batches.integration.inbound.model.BatchPaymentStatus;
import com.backbase.payments.batches.integration.inbound.model.BatchStatus;
import com.backbase.payments.batches.integration.inbound.model.GetBatchOrderResponse;
import com.backbase.payments.batches.integration.inbound.model.IntegrationBatchPayment;
import com.backbase.payments.batches.integration.inbound.model.IntegrationMultipleUpdatableBatchPayment;
import com.backbase.payments.batches.integration.inbound.model.OriginatorAccountIdentification;
import com.backbase.payments.batches.integration.inbound.model.PutBatchOrderRequest;
import com.backbase.payments.batches.integration.inbound.model.PutBatchPaymentsRequest;
import com.backbase.payments.batches.integration.inbound.webclient.api.BatchOrdersApi;
import com.backbase.payments.batches.integration.outbound.model.PostBatchOrderRequest;
import com.google.common.collect.Lists;

/**
 * @author balazst
 * 
 * This implementation uses Spring WebClient API client
 *
 */
@Component
@Slf4j
public class WebClientBatchOrderExecutor implements BatchOrderExecutor {
    private final int PAYMENT_ITEMS_PAGE_SIZE = 100;

    private final Queue<PostBatchOrderRequest> batchRequestQueue;

    private final BatchOrdersApi batchOrdersApi;

    public WebClientBatchOrderExecutor(
        @Qualifier("webClientBatchOrdersApi") BatchOrdersApi batchOrdersApi,
        Queue<PostBatchOrderRequest> batchRequestQueue) {
        this.batchRequestQueue = batchRequestQueue;
        this.batchOrdersApi = batchOrdersApi;
    }

    /**
     * Scheduled method, periodically picking up the batch orders from the queue created by the BatchOrderController then retrieves the batch information and payments, 
     * then marks the batch done
     */
    @Override
    public void execute() {
        int cnt = 0;
        log.info("Batch scheduler start", batchRequestQueue);
        while (!batchRequestQueue.isEmpty()) {
            PostBatchOrderRequest queuedBatchOrder = batchRequestQueue.poll();
            log.info("Batch	scheduler process item {}", queuedBatchOrder.getId());

            batchOrdersApi.getBatchOrder(queuedBatchOrder.getId()).subscribe(
                    batchOrder -> {
                        // Update batch status to DOWNLOADING
                        setBatchStatus(batchOrder, BatchStatus.DOWNLOADING,
                                null).subscribe(batchStatus -> {
                                    // Get all payments from the batch (Note: we must keep the order)
                                    int lastPageNumber =
                                            (int) Math.ceil(batchOrder.getTotalTransactionsCount().doubleValue()
                                                    / PAYMENT_ITEMS_PAGE_SIZE);
                                    Flux.range(0, lastPageNumber).flatMapSequential(
                                            pageNumber -> batchOrdersApi
                                                    .getBatchPayments(batchOrder.getId(), pageNumber,
                                                            PAYMENT_ITEMS_PAGE_SIZE)
                                                    .flatMapIterable(pageResponse -> pageResponse.getBatchPayments()))
                                            .collectList()
                                            .subscribe(paymentItems -> processDownloadedBatch(
                                                    batchOrder.status(batchStatus),
                                                    paymentItems));
                                });
                    },
                    error -> {
                        // Real word scenario the batch order should be rescheduled or least marked failed depending on the nature of the problem
                        // and which stage the problem occurred
                        log.warn("Error processing batch order with ID {}", queuedBatchOrder.getId(), error);
                    });

            cnt++;
        }
        log.info("Batch process scheduler end, processed {} messages", cnt);
    }

    /** Set the batchOrder status based if the expectedStatus match
     * @param batchOrderId
     * @param expectedStatus
     * @param status
     * @param reasonText
     * @return the updated status (same as the status incoming parameter)
     */
    private Mono<BatchStatus> setBatchStatus(GetBatchOrderResponse batchOrder, BatchStatus status,
            String reasonText) {
        return batchOrdersApi.putBatchOrder(
                batchOrder.getId(),
                batchOrder.getStatus().getValue(),
                new PutBatchOrderRequest().status(status).reasonText(reasonText))
                .doOnError(e -> {
                    log.error("Unable to set batch order {} status to {} (expected status: {})", batchOrder.getId(),
                            status.getValue(), batchOrder.getStatus().getValue(), e);
                }).onErrorResume(e -> Mono.empty())
                .map(it -> it.getStatus()).doOnSuccess(batchStatus -> batchOrder.status(batchStatus));
    }

    private void processDownloadedBatch(GetBatchOrderResponse batchOrder, List<IntegrationBatchPayment> paymentItems) {
        if (paymentItems.size() != batchOrder.getTotalTransactionsCount().intValue()) {
            // Update batch status to REJECTED
            setBatchStatus(batchOrder, BatchStatus.REJECTED,
                    "Payment item count mismatch");
        } else {
            // Update batch status to ACCEPTED
            setBatchStatus(batchOrder, BatchStatus.ACCEPTED, null).subscribe(batchStaus -> {
                // "Process" payments
                List<IntegrationBatchPayment> failedPaymentItems = processBatchPayments(batchOrder.getId(),
                        batchOrder.getAccount(), paymentItems);
                // Handle failed payment items (set the individual paymentItem status to rejected
                // Note: the batchOrdersApi.putBatchPayments has limit 1000 on paymentItems, e.g the failedPaymentItems must be partitioned by 1000
                Lists.partition(failedPaymentItems, 1000).forEach(partitionedFailedPaymentItems -> {
                    if (!failedPaymentItems.isEmpty()) {
                        List<String> failedPaymentItemIds = partitionedFailedPaymentItems.stream()
                                .map(it -> it.getId())
                                .collect(Collectors.toList());
                        batchOrdersApi.putBatchPayments(
                                batchOrder.getId(),
                                batchStaus.getValue(),
                                new PutBatchPaymentsRequest().batchPayments(
                                        Collections.singletonList(
                                                new IntegrationMultipleUpdatableBatchPayment()
                                                        .ids(failedPaymentItemIds)
                                                        .status(BatchPaymentStatus.REJECTED))))
                                .doOnError(
                                        e -> log.error("Unable to mark batch order {} payment items [{}] as REJECTED",
                                                batchOrder.getId(), String.join(", ", failedPaymentItemIds), e))
                                .onErrorResume(e -> Mono.empty());
                    }
                });
                // Update batch status to PROCESSED
                setBatchStatus(batchOrder, BatchStatus.PROCESSED, null);
            });
        }
    }

    /** Dummy method for processing payments
     * @param batchOrderId
     * @param account
     * @param paymentItems
     * @return the list of items failed to process
     */
    private List<IntegrationBatchPayment> processBatchPayments(String batchOrderId,
            OriginatorAccountIdentification account, List<IntegrationBatchPayment> paymentItems) {
        List<IntegrationBatchPayment> invalidItems = new ArrayList<>();
        log.info("Processing batch with batchId: {} size: {}", batchOrderId, paymentItems.size());
        paymentItems.forEach(paymentItem -> {
            if (processPaymentItem(account, paymentItem)) {
                log.info("Payment processed with batchId: {} paymentId: {}", batchOrderId, paymentItem.getId());
            } else {
                invalidItems.add(paymentItem);
                log.info("Unable to process payment with batchId: {} paymentId: {}", batchOrderId, paymentItem.getId());
            }
        });
        log.info("Processed batch with batchId: {} size: {}", batchOrderId, paymentItems.size());
        return invalidItems;
    }

    /** Dummy payment item processor
     * @param account
     * @param paymentItem
     * @return false if the payment item not processable
     */
    private boolean processPaymentItem(OriginatorAccountIdentification account, IntegrationBatchPayment paymentItem) {
        // This method always "success" (returns true) on processing payment items, you should return false if the item is not processable
        // Note: you may implement retry instead of return false if temporary error occurred
        return true;
    }

    @Override
    public ExampleMode getType() {
        return ExampleMode.WEB_CLIENT;
    }
}
