package com.mybank.payments.batches.integration;

import com.backbase.batches.nacha.model.result.BankResult;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.commons.lang3.tuple.Pair;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.backbase.payments.batches.integration.outbound.model.PostBatchOrderRequest;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class Application extends SpringBootServletInitializer {
    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    public Queue<PostBatchOrderRequest> batchRequestQueue() {
        return new ConcurrentLinkedQueue<>();
    }

    @Bean
    public Queue<Pair<String, BankResult>> processedResultsPerFileQueue() {
        return new ConcurrentLinkedQueue<>();
    }

}