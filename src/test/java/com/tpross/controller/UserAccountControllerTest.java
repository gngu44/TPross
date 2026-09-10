package com.tpross.controller;

import com.tpross.dto.AccountResponse;
import com.tpross.dto.CreateUserRequest;
import com.tpross.dto.UserResponse;
import com.tpross.exception.DuplicateEmailException;
import com.tpross.exception.ResourceNotFoundException;
import com.tpross.security.SecurityConfig;
import com.tpross.service.AccountService;
import com.tpross.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({UserController.class, AccountController.class})
@Import(SecurityConfig.class)
class UserAccountControllerTest {

    private static final String VALID_USER = """
            {"email":"  Alice@Example.com  ","password":"development-password"}
            """;
    private static final Instant CREATED_AT = Instant.parse("2026-10-01T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService users;

    @MockitoBean
    private AccountService accounts;

    @Test
    void createsUserWithoutCsrfOrAuthenticationAndReturnsOnlyPublicFields() throws Exception {
        when(users.createUser(any())).thenReturn(new UserResponse(1L, "alice@example.com", CREATED_AT));
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(VALID_USER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        verify(users).createUser(new CreateUserRequest("alice@example.com", "development-password"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"email\":\"not-an-email\",\"password\":\"development-password\"}",
            "{\"email\":\"alice@example.com\",\"password\":\"short\"}",
            "{\"email\":\"alice@example.com\",\"password\":\"        \"}",
            "{\"email\":null,\"password\":\"development-password\"}",
            "{\"email\":\"alice@example.com\"}"
    })
    void rejectsInvalidUserFields(String body) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors").isMap())
                .andExpect(jsonPath("$.instance").value("/api/users"))
                .andExpect(content().string(not(containsString("development-password"))));
        verifyNoInteractions(users);
    }

    @Test
    void rejectsOverlongPassword() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"password\":\"" + "x".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "", "{\"email\":\"alice@example.com\",\"password\":\"development-password\",\"id\":99}"})
    void rejectsMalformedMissingOrUnsupportedUserInput(String body) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(users);
    }

    @Test
    void returnsConflictForDuplicateEmail() throws Exception {
        when(users.createUser(any())).thenThrow(new DuplicateEmailException());
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(VALID_USER))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A user with this email already exists."));
    }

    @Test
    void handlesDatabaseUniquenessRaceWithoutExposingSql() throws Exception {
        when(users.createUser(any())).thenThrow(new DataIntegrityViolationException("internal SQL details"));
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(VALID_USER))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString("internal SQL details"))));
    }

    @Test
    void createsAccountWithNoBodyAndProvidesRetrievalLocation() throws Exception {
        when(accounts.createAccount(1L)).thenReturn(accountResponse());
        mvc.perform(post("/api/users/1/accounts"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/accounts/2"))
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.balance").value(1000.00));
    }

    @Test
    void createsAccountWithEmptyJsonObject() throws Exception {
        when(accounts.createAccount(1L)).thenReturn(accountResponse());
        mvc.perform(post("/api/users/1/accounts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsClientSuppliedBalance() throws Exception {
        mvc.perform(post("/api/users/1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"balance\":999999.00}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(accounts);
    }

    @Test
    void retrievesAccountAsADto() throws Exception {
        when(accounts.getAccount(2L)).thenReturn(accountResponse());
        mvc.perform(get("/api/accounts/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.balance").value(1000.00))
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidPathIds(String id) throws Exception {
        mvc.perform(get("/api/accounts/" + id)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/users/" + id + "/accounts")).andExpect(status().isBadRequest());
        verifyNoInteractions(accounts);
    }

    @Test
    void missingUserReturnsNotFound() throws Exception {
        when(accounts.createAccount(99L)).thenThrow(new ResourceNotFoundException("User", 99L));
        mvc.perform(post("/api/users/99/accounts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("User 99 was not found."));
    }

    @Test
    void missingAccountReturnsNotFound() throws Exception {
        when(accounts.getAccount(99L)).thenThrow(new ResourceNotFoundException("Account", 99L));
        mvc.perform(get("/api/accounts/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Account 99 was not found."));
    }

    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.TEXT_PLAIN).content(VALID_USER))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void unexpectedFailureDoesNotExposeInternalDetails() throws Exception {
        when(accounts.getAccount(2L)).thenThrow(new IllegalStateException("private internal details"));
        mvc.perform(get("/api/accounts/2"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("private internal details"))));
    }

    private AccountResponse accountResponse() {
        return new AccountResponse(2L, 1L, new BigDecimal("1000.00"), CREATED_AT);
    }
}
