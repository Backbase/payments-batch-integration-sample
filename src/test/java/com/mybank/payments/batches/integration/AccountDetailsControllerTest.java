package com.mybank.payments.batches.integration;

import static org.hamcrest.core.Is.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@WebMvcTest(AccountDetailsController.class)
@AutoConfigureMockMvc(addFilters = false)
class AccountDetailsControllerTest {

    private static final String TEST_COMPANY_ID = "1234567890";

    @Autowired
    MockMvc mockMvc;

    @Test
    void testGetAccountDetails() throws Exception {

        mockMvc.perform(
                MockMvcRequestBuilders
                    .get("/service-api/v2/account-details")
                    .param("companyId", TEST_COMPANY_ID)
                    .param("companyName", "TEST")
                    .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountNumber", is(TEST_COMPANY_ID)));

    }

}
