package com.mybank.payments.batches.integration.executor.resttemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.backbase.payments.batches.integration.inbound.model.BatchStatus;
import com.backbase.payments.batches.integration.inbound.model.GetBatchOrderResponse;
import com.backbase.payments.batches.integration.inbound.model.GetBatchPaymentsResponse;
import com.backbase.payments.batches.integration.inbound.model.IntegrationBatchPayment;
import com.backbase.payments.batches.integration.inbound.model.PutBatchOrderRequest;
import com.backbase.payments.batches.integration.inbound.model.PutBatchOrderResponse;
import com.backbase.payments.batches.integration.inbound.resttemplate.api.BatchOrdersApi;
import com.backbase.payments.batches.integration.outbound.model.PostBatchOrderRequest;

@ExtendWith(MockitoExtension.class)
class RestBatchOrderExecutorTest {
    final int PAYMENT_ITEMS_PAGE_SIZE = 100;

    @Mock
    BatchOrdersApi batchOrdersApi;

    @ParameterizedTest
    @ValueSource(ints = {1, 99, 100, 101})
    void testValid(int paymentItemCount) {
        Queue<PostBatchOrderRequest> batchRequestQueue = new LinkedList<>();
        String batchOrderId = UUID.randomUUID().toString();
        batchRequestQueue.add(
            new PostBatchOrderRequest()
                .id(batchOrderId)
        );

        // prepare mock
        Mockito.when(batchOrdersApi.getBatchOrder(Mockito.eq(batchOrderId))).thenReturn(
            new GetBatchOrderResponse()
                .id(batchOrderId)
                .status(BatchStatus.ACKNOWLEDGED)
                .totalTransactionsCount(BigDecimal.valueOf(paymentItemCount))
        );
        int pageCount = (int)Math.ceil((double)paymentItemCount/PAYMENT_ITEMS_PAGE_SIZE);
        for (int pageNumber = 0; pageNumber < pageCount; pageNumber++) {
            Mockito.when(batchOrdersApi.getBatchPayments(Mockito.eq(batchOrderId), Mockito.eq(pageNumber), Mockito.eq(PAYMENT_ITEMS_PAGE_SIZE))).thenReturn(
                new GetBatchPaymentsResponse()
                    .batchPayments(generatePaymentItems(pageNumber, PAYMENT_ITEMS_PAGE_SIZE, paymentItemCount))
                    .totalBatchPayments((long)paymentItemCount)
            );
        }
        mockStatusTransition(batchOrderId, BatchStatus.ACKNOWLEDGED, BatchStatus.DOWNLOADING);
        mockStatusTransition(batchOrderId, BatchStatus.DOWNLOADING, BatchStatus.ACCEPTED);
        mockStatusTransition(batchOrderId, BatchStatus.ACCEPTED, BatchStatus.PROCESSED);

        // execute
        RestBatchOrderExecutor batchOrderExecutor = new RestBatchOrderExecutor(batchOrdersApi, batchRequestQueue);
        batchOrderExecutor.execute();

        // validate
        Mockito.verify(batchOrdersApi, Mockito.times(pageCount))
            .getBatchPayments(Mockito.eq(batchOrderId), Mockito.any(), Mockito.eq(PAYMENT_ITEMS_PAGE_SIZE));

        ArgumentCaptor<PutBatchOrderRequest> putBatchOrderRequestCaptor = ArgumentCaptor.forClass(PutBatchOrderRequest.class);
        Mockito.verify(batchOrdersApi)
            .putBatchOrder(Mockito.eq(batchOrderId), Mockito.eq(BatchStatus.ACCEPTED.getValue()), putBatchOrderRequestCaptor.capture());

        Assertions.assertEquals(BatchStatus.PROCESSED, putBatchOrderRequestCaptor.getValue().getStatus());
    }

    @Test
    void testRejected() {
        final int reportedPaymentItemCount = 1;
        final int returnedPaymentItemCount = 2;

        Queue<PostBatchOrderRequest> batchRequestQueue = new LinkedList<>();
        String batchOrderId = UUID.randomUUID().toString();
        batchRequestQueue.add(
            new PostBatchOrderRequest()
                .id(batchOrderId)
        );

        // prepare mock
        Mockito.when(batchOrdersApi.getBatchOrder(Mockito.eq(batchOrderId))).thenReturn(
            new GetBatchOrderResponse()
                .id(batchOrderId)
                .status(BatchStatus.ACKNOWLEDGED)
                .totalTransactionsCount(BigDecimal.valueOf(reportedPaymentItemCount))
        );
        Mockito.when(batchOrdersApi.getBatchPayments(Mockito.eq(batchOrderId), Mockito.eq(0), Mockito.eq(PAYMENT_ITEMS_PAGE_SIZE))).thenReturn(
            new GetBatchPaymentsResponse()
                .batchPayments(generatePaymentItems(0, PAYMENT_ITEMS_PAGE_SIZE, returnedPaymentItemCount))
                .totalBatchPayments((long)returnedPaymentItemCount)
        );
        mockStatusTransition(batchOrderId, BatchStatus.ACKNOWLEDGED, BatchStatus.DOWNLOADING);
        mockStatusTransition(batchOrderId, BatchStatus.DOWNLOADING, BatchStatus.REJECTED);

        // execute
        RestBatchOrderExecutor batchOrderExecutor = new RestBatchOrderExecutor(batchOrdersApi, batchRequestQueue);
        batchOrderExecutor.execute();

        // validate
        Mockito.verify(batchOrdersApi)
            .getBatchPayments(Mockito.eq(batchOrderId), Mockito.eq(0), Mockito.eq(PAYMENT_ITEMS_PAGE_SIZE));

        ArgumentCaptor<PutBatchOrderRequest> putBatchOrderRequestCaptor = ArgumentCaptor.forClass(PutBatchOrderRequest.class);
        Mockito.verify(batchOrdersApi)
            .putBatchOrder(Mockito.eq(batchOrderId), Mockito.eq(BatchStatus.DOWNLOADING.getValue()), putBatchOrderRequestCaptor.capture());

        Assertions.assertEquals(BatchStatus.REJECTED, putBatchOrderRequestCaptor.getValue().getStatus());
    }

    private void mockStatusTransition(String batchOrderId, BatchStatus expectedStatus, BatchStatus status) {
        Mockito.when(batchOrdersApi.putBatchOrder(Mockito.eq(batchOrderId), Mockito.eq(expectedStatus.getValue()), Mockito.argThat(p->p.getStatus() == status))).thenReturn(
            new PutBatchOrderResponse()
                .id(batchOrderId)
                .status(status)
        );
    }

    private List<IntegrationBatchPayment> generatePaymentItems(int page, int pageSize, int totalCount) {
        int first = page*pageSize;
        assert first <= totalCount;
        int last = first+pageSize;
        if (last > totalCount) last = totalCount;
        List<IntegrationBatchPayment> paymentItems = new ArrayList<>(last-first);
        for(int i=first; i<last; i++) {
            paymentItems.add(new IntegrationBatchPayment()
                .id(UUID.randomUUID().toString())
                .description("paymentItem#"+i)
            );
        }
        return paymentItems;
    }

}