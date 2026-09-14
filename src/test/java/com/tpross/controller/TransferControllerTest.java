package com.tpross.controller;

import com.tpross.dto.CreateTransferRequest;
import com.tpross.dto.TransferResponse;
import com.tpross.exception.InvalidTransferException;
import com.tpross.exception.ResourceNotFoundException;
import com.tpross.exception.TransferConflictException;
import com.tpross.model.TransactionStatus;
import com.tpross.security.SecurityConfig;
import com.tpross.service.TransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
@Import(SecurityConfig.class)
class TransferControllerTest {

    private static final String VALID_REQUEST = """
            {"sourceAccountId":1,"destinationAccountId":2,"amount":25.50}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TransferService transfers;

    @Test
    void createsTransferWithoutAuthenticationOrCsrfAndReturnsPublicDto() throws Exception {
        Instant timestamp = Instant.parse("2026-10-01T12:00:00Z");
        when(transfers.createTransfer(any())).thenReturn(new TransferResponse(3L, 1L, 2L,
                new BigDecimal("25.50"), TransactionStatus.COMPLETED, timestamp));
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value(3))
                .andExpect(jsonPath("$.sourceAccountId").value(1))
                .andExpect(jsonPath("$.destinationAccountId").value(2))
                .andExpect(jsonPath("$.amount").value(25.50))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.timestamp").value(timestamp.toString()))
                .andExpect(jsonPath("$.sourceAccount").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        verify(transfers).createTransfer(new CreateTransferRequest(1L, 2L, new BigDecimal("25.50")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-0.01", "0.001", "1.001", "100000000000000000.00"})
    void rejectsInvalidAmounts(String amount) throws Exception {
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":1,\"destinationAccountId\":2,\"amount\":" + amount + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
        verifyNoInteractions(transfers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "0", "-1", "1.5", "9223372036854775808", "\"abc\""})
    void rejectsInvalidAccountIds(String id) throws Exception {
        for (String field : new String[]{"sourceAccountId", "destinationAccountId"}) {
            String body = VALID_REQUEST.replace(field + "\":" + (field.startsWith("source") ? "1" : "2"),
                    field + "\":" + id);
            mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(transfers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{", "{}",
            "{\"sourceAccountId\":1,\"destinationAccountId\":2}",
            "{\"sourceAccountId\":1,\"amount\":1}",
            "{\"destinationAccountId\":2,\"amount\":1}",
            "{\"sourceAccountId\":1,\"destinationAccountId\":2,\"amount\":1,\"status\":\"COMPLETED\"}"})
    void rejectsMissingMalformedOrUnsupportedInput(String body) throws Exception {
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(transfers);
    }

    @Test
    void sameAccountReturns400() throws Exception {
        when(transfers.createTransfer(any())).thenThrow(new InvalidTransferException());
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Source and destination accounts must be different."));
    }

    @Test
    void missingAccountReturns404() throws Exception {
        when(transfers.createTransfer(any())).thenThrow(new ResourceNotFoundException("Account", 2L));
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isNotFound());
    }

    @Test
    void insufficientFundsReturns409() throws Exception {
        when(transfers.createTransfer(any())).thenThrow(new TransferConflictException("Source account has insufficient funds."));
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Source account has insufficient funds."));
    }

    @Test
    void lockingFailureReturns409WithoutDatabaseDetails() throws Exception {
        when(transfers.createTransfer(any())).thenThrow(new PessimisticLockingFailureException("private SQL"));
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("private SQL"))));
    }

    @Test
    void unsupportedContentTypeReturns415() throws Exception {
        mvc.perform(post("/api/transfers").contentType(MediaType.TEXT_PLAIN).content(VALID_REQUEST))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(transfers);
    }
}
