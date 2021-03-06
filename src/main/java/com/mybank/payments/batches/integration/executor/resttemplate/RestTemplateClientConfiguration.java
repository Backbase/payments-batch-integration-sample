package com.mybank.payments.batches.integration.executor.resttemplate;

import com.backbase.buildingblocks.communication.http.HttpCommunicationConfiguration;
import javax.validation.constraints.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import com.backbase.payments.batches.integration.inbound.resttemplate.ApiClient;
import com.backbase.payments.batches.integration.inbound.resttemplate.api.BatchOrdersApi;

@Configuration
@ConfigurationProperties("backbase.communication.services.dbs.payment-order-service")
public class RestTemplateClientConfiguration {

    @Value("${backbase.communication.services.dbs.payment-order-service.service-id:payment-order-service}")
    private String serviceId;

    @Value("${backbase.communication.http.default-scheme:http}")
    @Pattern(regexp = "https?")
    private String scheme;

    @Autowired
    @Qualifier("interServiceRestTemplate")
    private RestTemplate restTemplate;

    @Bean("restTemplateBatchOrdersApi")
    public BatchOrdersApi batchOrdersApi() {
        return new BatchOrdersApi(createApiClient());
    }

    private ApiClient createApiClient() {
        ApiClient apiClient = new ApiClient(restTemplate);
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