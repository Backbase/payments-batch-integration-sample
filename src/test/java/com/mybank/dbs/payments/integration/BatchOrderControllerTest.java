package com.mybank.dbs.payments.integration;

import java.time.LocalDate;
import java.util.Queue;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.backbase.payments.integration.model.AccountIdentification;
import com.backbase.payments.integration.model.AccountIdentificationIdentification;
import com.backbase.payments.integration.model.BatchStatus;
import com.backbase.payments.integration.model.Currency;
import com.backbase.payments.integration.model.IntegrationSchemeNames;
import com.backbase.payments.integration.model.PostBatchOrderRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@WebMvcTest(BatchOrderController.class)
@AutoConfigureMockMvc(addFilters = false)
class BatchOrderControllerTest {
    
	@Autowired
	MockMvc mockMvc;
	
	@MockBean
	Queue<PostBatchOrderRequest> batchRequestQueue;
	
	private ObjectMapper om = new ObjectMapper()
		.registerModule(new Jdk8Module())
		.registerModule(new JavaTimeModule());

	@Test
	void testReceive() throws Exception {
		Mockito.when(batchRequestQueue.offer(Mockito.any())).thenReturn(true);
		Mockito.when(batchRequestQueue.isEmpty()).thenReturn(true);
		
		String batchOrderId = UUID.randomUUID().toString();
		
		PostBatchOrderRequest postBatchOrderRequest = new PostBatchOrderRequest()
			.id(batchOrderId)
			.status(BatchStatus.READY)
			.totalInstructedAmount(
				new Currency()
					.amount("100")
					.currencyCode("EUR"))
			.totalTransactionsCount(1)
			.account(
				new AccountIdentification()
					.arrangementId(UUID.randomUUID().toString())
					.identification(
						new AccountIdentificationIdentification()
							.identification("NL81APMN0449440095")
							.schemeName(IntegrationSchemeNames.IBAN)
					)
			)
			.requestedExecutionDate(LocalDate.now())
			.type("T1");

		MockHttpServletResponse response = mockMvc.perform(
			MockMvcRequestBuilders
				.post("/service-api/v2/batch-orders")
				.content(om.writeValueAsBytes(postBatchOrderRequest))
				.accept(MediaType.APPLICATION_JSON)
				.contentType(MediaType.APPLICATION_JSON)
			).andReturn().getResponse();
		
		Assertions.assertEquals(HttpStatus.ACCEPTED.value(), response.getStatus());

		ArgumentCaptor<PostBatchOrderRequest> postBatchOrderRequestCaptor = ArgumentCaptor.forClass(PostBatchOrderRequest.class);
		Mockito.verify(batchRequestQueue).offer(postBatchOrderRequestCaptor.capture());
		
		Assertions.assertEquals(batchOrderId, postBatchOrderRequestCaptor.getValue().getId());
	}

}
