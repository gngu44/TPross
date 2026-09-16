package com.tpross.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpross.model.User;
import com.tpross.repository.AccountRepository;
import com.tpross.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Container disposal removes the schema; avoid shutdown DDL after PostgreSQL has stopped.
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "app.accounts.starting-balance=4321.09"
})
@AutoConfigureMockMvc
@Testcontainers
class UserAccountApiIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private UserRepository users;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearDisposableDatabase() {
        jdbc.update("delete from transactions");
        jdbc.update("delete from accounts");
        jdbc.update("delete from users");
    }

    @Test
    void createsUserAndAccountsAndRetrievesPersistedDto() throws Exception {
        JsonNode userResponse = createUser("  Alice@Example.com  ");
        long userId = userResponse.get("id").asLong();
        assertThat(userResponse.get("email").asText()).isEqualTo("alice@example.com");
        assertThat(userResponse.has("password")).isFalse();
        assertThat(userResponse.has("passwordHash")).isFalse();
        User stored = users.findById(userId).orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo("development-password");
        assertThat(passwordEncoder.matches("development-password", stored.getPasswordHash())).isTrue();

        String response = mvc.perform(post("/api/users/" + userId + "/accounts"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long accountId = mapper.readTree(response).get("id").asLong();
        assertThat(accounts.findById(accountId).orElseThrow().getBalance())
                .isEqualByComparingTo(new BigDecimal("4321.09"));
        mvc.perform(get("/api/accounts/" + accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId))
                .andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.balance").value(4321.09))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.user").doesNotExist());
        mvc.perform(post("/api/users/" + userId + "/accounts")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"));
        assertThat(accounts.count()).isEqualTo(2);
    }

    @Test
    void rejectsDuplicateNormalizedEmailWithoutCreatingAnotherUser() throws Exception {
        createUser("alice@example.com");
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\" ALICE@EXAMPLE.COM \",\"password\":\"development-password\"}"))
                .andExpect(status().isConflict());
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void missingResourcesReturn404AndCreateNothing() throws Exception {
        mvc.perform(post("/api/users/999999/accounts")).andExpect(status().isNotFound());
        mvc.perform(get("/api/accounts/999999")).andExpect(status().isNotFound());
        assertThat(accounts.count()).isZero();
    }

    @Test
    void rejectsBalanceOverrideAndInvalidUserBeforePersistence() throws Exception {
        long userId = createUser("alice@example.com").get("id").asLong();
        mvc.perform(post("/api/users/" + userId + "/accounts")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"balance\":999999}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        assertThat(accounts.count()).isZero();
        assertThat(users.count()).isEqualTo(1);
    }

    private JsonNode createUser(String email) throws Exception {
        String response = mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new com.tpross.dto.CreateUserRequest(email, "development-password"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
}
