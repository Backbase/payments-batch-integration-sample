package com.mybank.dbs.payments.integration;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.backbase.payments.integration.model.PostBatchOrderRequest;

@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
public class Application extends SpringBootServletInitializer {
    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }
    
    @Bean
    public Queue<PostBatchOrderRequest> batchRequestQueue() {
    	return new ConcurrentLinkedQueue<PostBatchOrderRequest>();
    }
}