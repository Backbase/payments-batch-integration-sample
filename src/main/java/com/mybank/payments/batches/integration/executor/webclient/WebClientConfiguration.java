package com.mybank.payments.batches.integration.executor.webclient;

import java.text.DateFormat;

import javax.validation.constraints.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.backbase.buildingblocks.communication.http.HttpCommunicationConfiguration;
import com.backbase.buildingblocks.webclient.WebClientConstants;
import com.backbase.payments.batches.integration.inbound.webclient.ApiClient;
import com.backbase.payments.batches.integration.inbound.webclient.api.BatchOrdersApi;

@Configuration
@ConfigurationProperties("backbase.communication.services.dbs.payment-order-service")
public class WebClientConfiguration {

    @Value("${backbase.communication.services.dbs.payment-order-service.service-id:payment-order-service}")
    private String serviceId;

    @Value("${backbase.communication.http.default-scheme:http}")
    @Pattern(regexp = "https?")
    private String scheme;

    @Autowired
    @Qualifier(WebClientConstants.INTER_SERVICE_WEB_CLIENT_NAME)
    private WebClient webClient;

    @Autowired
    private DateFormat dateFormat;

    @Bean("webClientBatchOrdersApi")
    public BatchOrdersApi batchOrdersApi() {
        return new BatchOrdersApi(createApiClient());
    }

    private ApiClient createApiClient() {
        ApiClient apiClient = new ApiClient(webClient, null, dateFormat);
        apiClient.setBasePath(String.format("%s://%s", scheme, serviceId));
        apiClient.addDefaultHeader(HttpCommunicationConfiguration.INTERCEPTORS_ENABLED_HEADER, Boolean.TRUE.toString());
        return apiClient;
    }

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

}
