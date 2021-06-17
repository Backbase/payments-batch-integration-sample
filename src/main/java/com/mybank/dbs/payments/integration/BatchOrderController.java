package com.mybank.dbs.payments.integration;

import java.util.Queue;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.backbase.buildingblocks.presentation.errors.InternalServerErrorException;
import com.backbase.payments.integration.model.BatchStatus;
import com.backbase.payments.integration.model.PostBatchOrderRequest;
import com.backbase.payments.integration.model.PostBatchOrderResponse;
import com.backbase.payments.integration.outbound.api.BatchOrdersApi;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequiredArgsConstructor
@Slf4j
public class BatchOrderController implements BatchOrdersApi {
	
	private final Queue<PostBatchOrderRequest> batchRequestQueue;

     /**
     * Listen on notification about a batch created. This method just save the request in a queue and the BatchOrderExecutor will process that.
     */
	@Override
	public ResponseEntity<PostBatchOrderResponse> postBatches(@Valid @NotNull PostBatchOrderRequest postBatchOrderRequest) {
		log.info("Received batch order with id {} payload: {}", postBatchOrderRequest.getId(), postBatchOrderRequest);
		try {
			this.batchRequestQueue.offer(postBatchOrderRequest);
		} catch (Exception e) {
	      log.error("Error storing batch request", e);
	      throw new InternalServerErrorException().withMessage("Saving batch order failed");
		}
		log.info("Batch order stored with id {}", postBatchOrderRequest.getId());
		return ResponseEntity.accepted().body(new PostBatchOrderResponse().status(BatchStatus.ACKNOWLEDGED));
	}
}
