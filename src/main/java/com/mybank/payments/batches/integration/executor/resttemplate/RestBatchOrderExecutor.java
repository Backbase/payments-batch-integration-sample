package com.mybank.payments.batches.integration.executor.resttemplate;

import com.mybank.payments.batches.integration.executor.BatchOrderExecutor;
import com.mybank.payments.batches.integration.ExampleMode;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.backbase.payments.batches.integration.inbound.model.BatchPaymentStatus;
import com.backbase.payments.batches.integration.inbound.model.BatchStatus;
import com.backbase.payments.batches.integration.inbound.model.GetBatchOrderResponse;
import com.backbase.payments.batches.integration.inbound.model.GetBatchPaymentsResponse;
import com.backbase.payments.batches.integration.inbound.model.IntegrationBatchPayment;
import com.backbase.payments.batches.integration.inbound.model.IntegrationMultipleUpdatableBatchPayment;
import com.backbase.payments.batches.integration.inbound.model.OriginatorAccountIdentification;
import com.backbase.payments.batches.integration.inbound.model.PutBatchOrderRequest;
import com.backbase.payments.batches.integration.inbound.model.PutBatchPaymentsRequest;
import com.backbase.payments.batches.integration.inbound.resttemplate.api.BatchOrdersApi;
import com.backbase.payments.batches.integration.outbound.model.PostBatchOrderRequest;
import com.google.common.collect.Lists;

/**
 * @author balazst
 *
 * This implementation uses Spring RestTemplate API client
 *
 */
@Component
@Slf4j
public class RestBatchOrderExecutor implements BatchOrderExecutor {
    private final int PAYMENT_ITEMS_PAGE_SIZE = 100;

    private final Queue<PostBatchOrderRequest> batchRequestQueue;

    private final BatchOrdersApi batchOrdersApi;

    public RestBatchOrderExecutor(
        @Qualifier("restTemplateBatchOrdersApi") BatchOrdersApi batchOrdersApi,
        Queue<PostBatchOrderRequest> batchRequestQueue) {
        this.batchRequestQueue = batchRequestQueue;
        this.batchOrdersApi = batchOrdersApi;
    }

    /**
     * Scheduled method, periodically picking up the batch orders from the queue created by the BatchOrderController then retrieves the batch information and payments,
     * then marks the batch done
     * <p>
     * NB total transactions count reported by `GET /integration-api/v2/batch-orders/{batchOrderId}` can be less than
     * the number of transactions retrieved from `GET /integration-api/v2/batch-orders/{batchOrderId}/batch-payments`
     * due to possible presence of "hidden" transactions (e.g. transactions representing batch balancing information)
     */
    @Override
    public void execute() {
        int cnt = 0;
        log.info("Batch scheduler start", batchRequestQueue);
        while (!batchRequestQueue.isEmpty()) {
            PostBatchOrderRequest batchItem = batchRequestQueue.poll();
            log.info("Batch scheduler process item {}", batchItem.getId());
            GetBatchOrderResponse getBatchOrderResponse = batchOrdersApi.getBatchOrder(batchItem.getId());
            BatchStatus batchStatus = getBatchOrderResponse.getStatus();
            int totalTransactionsCount = getBatchOrderResponse.getTotalTransactionsCount().intValue();

            // Update batch status to DOWNLOADING
            batchStatus = setBatchStatus(batchItem.getId(), batchStatus, BatchStatus.DOWNLOADING, null);
            // Get all payments from the batch
            List<IntegrationBatchPayment> paymentItems = new ArrayList<>();
            GetBatchPaymentsResponse batchPaymentsResponse;
            long totalBatchPayments;
            int pageNumber = 0;
            do {
                batchPaymentsResponse =
                        batchOrdersApi.getBatchPayments(batchItem.getId(), pageNumber++, PAYMENT_ITEMS_PAGE_SIZE);
                totalBatchPayments = batchPaymentsResponse.getTotalBatchPayments();
                paymentItems.addAll(batchPaymentsResponse.getBatchPayments());
            } while ((paymentItems.size() < totalBatchPayments)
                    && (batchPaymentsResponse.getBatchPayments().size() > 0));

            List<IntegrationBatchPayment> hiddenPayments = paymentItems.stream()
                .filter(IntegrationBatchPayment::getHidden)
                .collect(Collectors.toList());

            List<IntegrationBatchPayment> actualPayments =
                paymentItems.stream()
                    .filter(Predicate.not(IntegrationBatchPayment::getHidden))
                    .collect(Collectors.toList());

            if (paymentItems.size() != totalBatchPayments || actualPayments.size() != totalTransactionsCount) {
                // Update batch status to REJECTED
                batchStatus = setBatchStatus(batchItem.getId(), batchStatus, BatchStatus.REJECTED,
                        "Payment item count mismatch");
            } else {
                // Update batch status to ACCEPTED
                batchStatus = setBatchStatus(batchItem.getId(), batchStatus, BatchStatus.ACCEPTED, null);

                // Handle information delivered in hidden payments here
                processHiddenBatchPayments(hiddenPayments);

                // "Process" payments
                List<IntegrationBatchPayment> failedPaymentItems =
                        processBatchPayments(batchItem.getId(), getBatchOrderResponse.getAccount(), actualPayments);
                // Handle failed payment items (set the individual paymentItem status to rejected
                // Note: the batchOrdersApi.putBatchPayments has limit 1000 on paymentItems, e.g the failedPaymentItems must be partitioned by 1000
                Lists.partition(failedPaymentItems, 1000).forEach(partitionedFailedPaymentItems -> {
                    if (!failedPaymentItems.isEmpty()) {
                        batchOrdersApi.putBatchPayments(
                                batchItem.getId(),
                                BatchStatus.ACCEPTED.getValue(),
                                new PutBatchPaymentsRequest().batchPayments(
                                        Collections.singletonList(
                                                new IntegrationMultipleUpdatableBatchPayment()
                                                        .ids(partitionedFailedPaymentItems.stream()
                                                                .map(it -> it.getId()).collect(Collectors.toList()))
                                                        .status(BatchPaymentStatus.REJECTED))));
                    }
                });
                // Update batch status to PROCESSED
                batchStatus = setBatchStatus(batchItem.getId(), batchStatus, BatchStatus.PROCESSED, null);
            }
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
    private BatchStatus setBatchStatus(String batchOrderId, BatchStatus expectedStatus, BatchStatus status,
            String reasonText) {
        return batchOrdersApi.putBatchOrder(
                batchOrderId,
                expectedStatus.getValue(),
                new PutBatchOrderRequest().status(status).reasonText(reasonText))
                .getStatus();
    }

    /**
     * Dummy hidden payments processor
     */
    private void processHiddenBatchPayments(List<IntegrationBatchPayment> paymentItems) {
        log.info("Processing hidden batch with size: {}", paymentItems.size());
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
        return ExampleMode.REST_TEMPLATE;
    }

}