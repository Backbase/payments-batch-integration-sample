package com.mybank.payments.batches.integration.executor.nachafile;

import static com.backbase.batches.service.integration.inbound.model.BatchStatus.ACCEPTED;
import static com.backbase.batches.service.integration.inbound.model.BatchStatus.DOWNLOADING;
import static com.backbase.batches.service.integration.inbound.model.BatchStatus.PROCESSED;
import static com.backbase.batches.service.integration.inbound.model.BatchStatus.REJECTED;
import static org.mockito.Mockito.verify;

import com.backbase.batches.nacha.config.BatchServiceClientAutoConfiguration;
import com.backbase.batches.nacha.config.NachaHandlerAutoConfiguration;
import com.backbase.batches.nacha.config.NachaWriterAutoConfiguration;
import com.backbase.batches.nacha.model.result.BankResult;
import com.backbase.batches.nacha.model.result.BankResult.BankBatchOrderResult;
import com.backbase.batches.nacha.model.result.BankResult.BankPaymentItemResult;
import com.backbase.batches.nacha.model.result.StatusInfo;
import com.backbase.batches.service.integration.inbound.api.BatchOrdersApi;
import com.backbase.batches.service.integration.inbound.model.BatchPaymentStatus;
import com.backbase.batches.service.integration.inbound.model.CreditDebitMixedIndicator;
import com.backbase.batches.service.integration.inbound.model.Currency;
import com.backbase.batches.service.integration.inbound.model.GetBatchOrderResponse;
import com.backbase.batches.service.integration.inbound.model.GetBatchPaymentsResponse;
import com.backbase.batches.service.integration.inbound.model.IntegrationBatchPayment;
import com.backbase.batches.service.integration.inbound.model.CreditDebitIndicator;
import com.backbase.batches.service.integration.inbound.model.IntegrationMultipleUpdatableBatchPayment;
import com.backbase.batches.service.integration.inbound.model.OriginatorAccountIdentification;
import com.backbase.batches.service.integration.inbound.model.PutBatchOrderRequest;
import com.backbase.batches.service.integration.inbound.model.PutBatchPaymentsRequest;
import com.backbase.payments.batches.integration.outbound.model.PostBatchOrderRequest;
import com.mybank.payments.batches.integration.executor.nachafile.NachaBatchOrderExecutorIT.NachaBatchOrderExecutorITConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedList;
import java.util.Queue;
import org.apache.commons.lang3.tuple.Pair;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
    BatchServiceClientAutoConfiguration.class,
    NachaWriterAutoConfiguration.class,
    NachaHandlerAutoConfiguration.class,
    NachaBatchOrderExecutor.class,
    ProcessedNachaFilesResultsExecutor.class,
    SimpleFileSequenceProvider.class,
    NachaBatchOrderExecutorITConfiguration.class
})
@TestPropertySource(value = {"/application-nacha-writer.properties"})
class NachaBatchOrderExecutorIT {

    @MockBean
    private @SuppressWarnings("nullness")
    @NonNull BatchOrdersApi batchOrdersApi;

    @Autowired
    private @SuppressWarnings("nullness")
    @NonNull NachaBatchOrderExecutor nachaBatchOrderExecutor;
    @Autowired
    private @SuppressWarnings("nullness")
    @NonNull ProcessedNachaFilesResultsExecutor processedNachaFilesExecutor;

    @Test
    void execute() {
        mockBatchOrderApiResponses();

        //Generate and upload NACHA file for 2 queued batches
        nachaBatchOrderExecutor.execute();

        //verifying setting batches to DOWNLOADING by the nacha-handler before nacha-writer generation
        verify(batchOrdersApi).putBatchOrder("BATCH1", "ACKNOWLEDGED", new PutBatchOrderRequest().status(DOWNLOADING));
        verify(batchOrdersApi).putBatchOrder("BATCH2", "ACKNOWLEDGED", new PutBatchOrderRequest().status(DOWNLOADING));

        //verifying setting batches & payments to ACCEPTED/REJECTED by the nacha-handler after nacha-writer result
        verify(batchOrdersApi).putBatchOrder("BATCH1", "DOWNLOADING", new PutBatchOrderRequest().status(ACCEPTED));
        verify(batchOrdersApi).putBatchOrder("BATCH2", "DOWNLOADING", new PutBatchOrderRequest().status(ACCEPTED));

        //Pass result to queue after bank PROCESSED NACHA file
        processedNachaFilesExecutor.getProcessedResultsPerFileQueue().add(
            Pair.of("BFT000A", mockProcessedBankResult())
        );
        //Execute action on PROCESSED Nacha files queue
        processedNachaFilesExecutor.execute();

        //verifying setting batches & payments to PROCESSED/REJECTED by the integration layer (ProcessedNachaFilesExecutor) after bank result
        verify(batchOrdersApi).putBatchOrder("BATCH1", "ACCEPTED", new PutBatchOrderRequest()
            .status(PROCESSED)
            .bankStatus("ApprovedBankStatus2")
        );
        verify(batchOrdersApi).putBatchPayments("BATCH1", "ACCEPTED", new PutBatchPaymentsRequest().addBatchPaymentsItem(
            new IntegrationMultipleUpdatableBatchPayment()
                .addIdsItem("C1B1P2")
                .status(BatchPaymentStatus.REJECTED)
                .bankStatus("RejectedBankStatus22")
                .reasonCode("ReasonCode22")
                .reasonText("ReasonText22")
                .errorDescription("ReasonDescription22")
        ));
        verify(batchOrdersApi).putBatchOrder("BATCH2", "ACCEPTED", new PutBatchOrderRequest()
            .status(REJECTED)
            .bankStatus("RejectedBankStatus1")
            .reasonCode("ReasonCode1")
            .reasonText("ReasonText1")
            .reasonDescription("ReasonDescription1"));
    }

    private void mockBatchOrderApiResponses() {
        LocalDate requestedExecDate = LocalDate.now();
        Mockito.when(batchOrdersApi.getBatchOrder("BATCH1")).thenReturn(
            new GetBatchOrderResponse()
                .id("BATCH1")
                .companyId("1000000000")
                .companyName("First Company")
                .batchReference("BR0001")
                .creditDebitMixedIndicator(CreditDebitMixedIndicator.CREDIT)
                .name("Batch1")
                .entryClass("CCD")
                .account(new OriginatorAccountIdentification().retrieved(Boolean.TRUE))
                .status(DOWNLOADING)
                .requestedExecutionDate(requestedExecDate)
                .totalCreditInstructedAmount(createDollarAmount("112.01"))
                .totalCreditTransactionsCount(BigDecimal.valueOf(2))
                .totalDebitInstructedAmount(createDollarAmount("0"))
                .totalDebitTransactionsCount(BigDecimal.valueOf(0))
                .totalInstructedAmount(createDollarAmount("112.01"))
                .totalTransactionsCount(BigDecimal.valueOf(2)));
        Mockito.when(batchOrdersApi.getBatchPayments(Mockito.eq("BATCH1"), Mockito.eq(0), Mockito.any()))
            .thenReturn(
                new GetBatchPaymentsResponse()
                    .totalBatchPayments(1l)
                    .addBatchPaymentsItem(new IntegrationBatchPayment()
                        .hidden(Boolean.FALSE)
                        .id("C1B1P1")
                        .reference("RefC1B1P1")
                        .transactionCode("29")
                        .creditDebitIndicator(CreditDebitIndicator.CREDIT)
                        .counterpartyAccountNumber("11111111")
                        .counterpartyName("CounterParty 1 1")
                        .counterpartyBankBranchCode("123456789")
                        .instructedAmount(createDollarAmount("100.01"))
                        .description("descr for C1B1P1"))
                    .addBatchPaymentsItem(new IntegrationBatchPayment()
                        .hidden(Boolean.FALSE)
                        .id("C1B1P2")
                        .reference("RefC1B1P2")
                        .transactionCode("39")
                        .creditDebitIndicator(CreditDebitIndicator.CREDIT)
                        .counterpartyAccountNumber("11111112")
                        .counterpartyName("CounterParty 1 2")
                        .counterpartyBankBranchCode("223456789")
                        .instructedAmount(createDollarAmount("12.00")))
            );
        Mockito.when(batchOrdersApi.getBatchOrder("BATCH2")).thenReturn(
            new GetBatchOrderResponse()
                .id("BATCH2")
                .companyId("1000000000")
                .companyName("First Company")
                .batchReference("BR0002")
                .creditDebitMixedIndicator(CreditDebitMixedIndicator.CREDIT)
                .name("Batch2")
                .entryClass("PPD")
                .account(new OriginatorAccountIdentification().retrieved(Boolean.TRUE))
                .status(DOWNLOADING)
                .requestedExecutionDate(requestedExecDate)
                .totalCreditInstructedAmount(createDollarAmount("126.98"))
                .totalCreditTransactionsCount(BigDecimal.valueOf(1))
                .totalDebitInstructedAmount(createDollarAmount("0"))
                .totalDebitTransactionsCount(BigDecimal.valueOf(0))
                .totalInstructedAmount(createDollarAmount("126.98"))
                .totalTransactionsCount(BigDecimal.valueOf(1)));
        Mockito.when(batchOrdersApi.getBatchPayments(Mockito.eq("BATCH2"), Mockito.eq(0), Mockito.any()))
            .thenReturn(
                new GetBatchPaymentsResponse()
                    .totalBatchPayments(1l)
                    .addBatchPaymentsItem(new IntegrationBatchPayment()
                        .hidden(Boolean.FALSE)
                        .id("C1B2P1")
                        .reference("RefC1B2P1")
                        .transactionCode("27")
                        .creditDebitIndicator(CreditDebitIndicator.CREDIT)
                        .counterpartyAccountNumber("21111111")
                        .counterpartyName("CounterParty 2 1")
                        .counterpartyBankBranchCode("987654321")
                        .instructedAmount(createDollarAmount("126.98"))));
    }

    private BankResult mockProcessedBankResult() {
        return BankResult.builder()
            .batch("1", BankBatchOrderResult.builder()
                .batchNumber("1")
                .statusInfo(new StatusInfo(false, "ApprovedBankStatus2", null, null, null))
                .entry("999999990000001", BankPaymentItemResult.builder()
                    .traceNumber("999999990000001")
                    .statusInfo(new StatusInfo(false, "ApprovedBankStatus21", null, null, null))
                    .build())
                .entry("999999990000002", BankPaymentItemResult.builder()
                    .traceNumber("999999990000002")
                    .statusInfo(new StatusInfo(true, "RejectedBankStatus22", "ReasonCode22", "ReasonText22",
                        "ReasonDescription22"))
                    .build())
                .build())
            .batch("2", BankBatchOrderResult.builder()
                .batchNumber("2")
                .statusInfo(new StatusInfo(true, "RejectedBankStatus1", "ReasonCode1", "ReasonText1",
                    "ReasonDescription1"))
                .build())
            .build();
    }

    public Queue<Pair<String, BankResult>> mockProcessedResultsPerFileQueue() {
        LinkedList<Pair<String, BankResult>> queue = new LinkedList<>();
        //Pass result to queue after bank PROCESSED NACHA file
        queue.add(
            Pair.of("BFT000A", mockProcessedBankResult())
        );
        return queue;
    }

    private static Currency createDollarAmount(String s) {
        return new Currency()
            .currencyCode("USD")
            .amount(s);
    }

    @TestConfiguration
    public static class NachaBatchOrderExecutorITConfiguration {

        @Bean
        public Queue<PostBatchOrderRequest> batchRequestQueue() {
            //initial state of BatchOrderExecutor queue - already 2 batches waiting
            LinkedList<PostBatchOrderRequest> queue = new LinkedList<>();
            queue.add(new PostBatchOrderRequest()
                .id("BATCH1")
                .companyId("1000000000")
                .companyName("First Company"));
            queue.add(new PostBatchOrderRequest()
                .id("BATCH2")
                .companyId("1000000000")
                .companyName("First Company"));
            return queue;
        }

        @Bean
        public Queue<Pair<String, BankResult>> processedResultsPerFileQueue() {
            return new LinkedList<>();
        }

    }

}
