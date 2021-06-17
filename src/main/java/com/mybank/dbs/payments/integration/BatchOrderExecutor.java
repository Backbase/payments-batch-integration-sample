package com.mybank.dbs.payments.integration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.backbase.payments.integration.inbound.api.BatchOrdersApi;
import com.backbase.payments.integration.model.AccountIdentification;
import com.backbase.payments.integration.model.BatchPaymentStatus;
import com.backbase.payments.integration.model.BatchStatus;
import com.backbase.payments.integration.model.GetBatchOrderResponse;
import com.backbase.payments.integration.model.GetBatchPaymentsResponse;
import com.backbase.payments.integration.model.IntegrationBatchPayment;
import com.backbase.payments.integration.model.IntegrationMultipleUpdatableBatchPayment;
import com.backbase.payments.integration.model.PostBatchOrderRequest;
import com.backbase.payments.integration.model.PutBatchOrderRequest;
import com.backbase.payments.integration.model.PutBatchPaymentsRequest;
import com.google.common.collect.Lists;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class BatchOrderExecutor {
	private final int PAYMENT_ITEMS_PAGE_SIZE = 100;

    private final BatchOrdersApi batchOrdersApi;
	private final Queue<PostBatchOrderRequest> batchRequestQueue;

    /**
     * Scheduled method, periodically picking up the batch orders from the queue created by the BatchOrderController then retrieves the batch information and payments, 
     * then marks the batch done
     */
    @Scheduled(fixedRate = 10000, initialDelay = 10000)
    public void execute() {
    	int cnt = 0;
		log.info("Batch scheduler start", batchRequestQueue);
		while (!batchRequestQueue.isEmpty()) {
			PostBatchOrderRequest batchItem = batchRequestQueue.poll();
    		log.info("Batch	scheduler process item {}", batchItem.getId());
            GetBatchOrderResponse getBatchOrderResponse = batchOrdersApi.getBatchOrder(batchItem.getId());
            BatchStatus batchStatus = getBatchOrderResponse.getStatus();
            int totalTransactionsCount = getBatchOrderResponse.getTotalTransactionsCount().intValue();

        	// Update batch status to DOWNLOADING
        	batchStatus = setBatchStatus(batchItem.getId(), batchStatus, BatchStatus.DOWNLOADING, null);
        	// Get all payments from the batch
        	List<IntegrationBatchPayment> paymentItems = new ArrayList<IntegrationBatchPayment>(totalTransactionsCount);
        	GetBatchPaymentsResponse batchPaymentsResponse;
        	int pageNumber = 0;
        	do {
        		batchPaymentsResponse = batchOrdersApi.getBatchPayments(batchItem.getId(), pageNumber++, PAYMENT_ITEMS_PAGE_SIZE);
        		paymentItems.addAll(batchPaymentsResponse.getBatchPayments());
        	} while ((paymentItems.size() < totalTransactionsCount) && (batchPaymentsResponse.getBatchPayments().size() > 0));
        	
        	if (paymentItems.size() != totalTransactionsCount) {
            	// Update batch status to REJECTED
    			batchStatus = setBatchStatus(batchItem.getId(), batchStatus, BatchStatus.REJECTED, "Payment item count mismatch");
        	} else {
	        	// Update batch status to ACCEPTED
	        	batchStatus = setBatchStatus(batchItem.getId(), batchStatus, BatchStatus.ACCEPTED, null);
	        	// "Process" payments
	        	List<IntegrationBatchPayment> failedPaymentItems = processBatchPayments(batchItem.getId(), getBatchOrderResponse.getAccount(), paymentItems);
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
			        						.ids(partitionedFailedPaymentItems.stream().map(it -> it.getId()).collect(Collectors.toList()))
			        						.status(BatchPaymentStatus.REJECTED)
	        						)
		        			)
		        		);
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
    private BatchStatus setBatchStatus(String batchOrderId, BatchStatus expectedStatus, BatchStatus status, String reasonText) {
    	return batchOrdersApi.putBatchOrder(
			batchOrderId, 
			expectedStatus.getValue(), 
			new PutBatchOrderRequest().status(status).reasonText(reasonText))
		.getStatus();
    }

    /** Dummy payment item processor
     * @param account
     * @param paymentItem
     * @return false if the payment item not processable
     */
    private boolean processPaymentItem(AccountIdentification account, IntegrationBatchPayment paymentItem) {
    	// This method always "success" (returns true) on processing payment items, you should return false if the item is not processable
    	// Note: you may implement retry instead of return false if temporary error occurred
    	return true;
    }
    
    /** Dummy method for processing payments
     * @param batchOrderId
     * @param account
     * @param paymentItems
     * @return the list of items failed to process
     */
    private List<IntegrationBatchPayment> processBatchPayments(String batchOrderId, AccountIdentification account, List<IntegrationBatchPayment> paymentItems) {
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

}
