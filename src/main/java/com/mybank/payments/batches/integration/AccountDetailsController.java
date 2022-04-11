package com.mybank.payments.batches.integration;

import com.backbase.payments.batches.integration.outbound.api.AccountDetailsApi;
import com.backbase.payments.batches.integration.outbound.model.AccountDetailsGetResponse;
import javax.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller to retrieve account details based on given company id & company name.
 * <p>This api is used for unbalanced batch uploads where originator account details can not be found
 * since there is no balancing record.</p>
 */
@RestController
@Slf4j
public class AccountDetailsController implements AccountDetailsApi {

    @Override
    public ResponseEntity<AccountDetailsGetResponse> getAccountDetails(@NotNull String companyId, String companyName) {

        log.info("Getting account details [companyId: {}, companyName: {}]", companyId, companyName);

        // In the example implementation we just return the requested companyId as an account number
        // Implement here the proper business logic to resolve account number
        String accountNumber = companyId;

        return ResponseEntity
            .ok(new AccountDetailsGetResponse().accountNumber(accountNumber));
    }
}
