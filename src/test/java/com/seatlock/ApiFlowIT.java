package com.seatlock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.support.AbstractIntegrationTest;
import com.seatlock.support.IntegrationTest;
import com.seatlock.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The API as a client actually meets it: register, browse, hold, pay, retry.
 *
 * <p>Goes through the HTTP layer rather than calling services directly, so the
 * security filter chain, request validation and the error envelope are all
 * covered by the same tests that cover the booking logic.
 */
@IntegrationTest
@AutoConfigureMockMvc
@DisplayName("Checkout API")
class ApiFlowIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestFixtures fixtures;

    @Test
    @DisplayName("Happy path: register, hold two seats, book them")
    void endToEndCheckout() throws Exception {
        var scenario = fixtures.scenario(5);
        String token = registerAndGetToken();

        String holdBody = json.writeValueAsString(java.util.Map.of(
                "eventSeatIds", scenario.seatIds().subList(0, 2)));

        JsonNode hold = postJson("/api/v1/events/" + scenario.eventId() + "/holds", holdBody, token, 201);
        assertThat(hold.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(hold.get("seats")).hasSize(2);
        assertThat(hold.get("totalCents").asLong()).isEqualTo(20_000L);
        assertThat(hold.get("secondsRemaining").asLong()).isPositive();

        String bookBody = json.writeValueAsString(java.util.Map.of("holdId", hold.get("holdId").asText()));
        JsonNode booking = postJson("/api/v1/bookings", bookBody, token, 201, UUID.randomUUID().toString());

        assertThat(booking.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(booking.get("reference").asText()).startsWith("SL-");
        assertThat(booking.get("seats")).hasSize(2);
        assertThat(booking.get("createdAt").isNull())
                .as("createdAt must be populated on the response, not left for a later re-read")
                .isFalse();
    }

    @Test
    @DisplayName("Retrying a booking with the same Idempotency-Key replays it instead of buying twice")
    void idempotentRetryReplaysTheOriginalBooking() throws Exception {
        var scenario = fixtures.scenario(2);
        String token = registerAndGetToken();
        String key = UUID.randomUUID().toString();

        String holdBody = json.writeValueAsString(java.util.Map.of(
                "eventSeatIds", scenario.seatIds().subList(0, 1)));
        JsonNode hold = postJson("/api/v1/events/" + scenario.eventId() + "/holds", holdBody, token, 201);
        String bookBody = json.writeValueAsString(java.util.Map.of("holdId", hold.get("holdId").asText()));

        JsonNode first = postJson("/api/v1/bookings", bookBody, token, 201, key);

        // Exactly what a mobile client does when the first response is lost.
        MvcResult replayed = mvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andReturn();

        JsonNode second = json.readTree(replayed.getResponse().getContentAsString());
        assertThat(second.get("id").asLong())
                .as("the retry must return the original booking, not a new one")
                .isEqualTo(first.get("id").asLong());
        assertThat(second.get("reference").asText()).isEqualTo(first.get("reference").asText());

        // And the user owns one booking, not two.
        MvcResult list = mvc.perform(get("/api/v1/bookings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        assertThat(json.readTree(list.getResponse().getContentAsString()).get("totalElements").asInt())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Reusing an Idempotency-Key for a different body is rejected")
    void reusingAKeyForADifferentRequestIsRejected() throws Exception {
        var scenario = fixtures.scenario(2);
        String token = registerAndGetToken();
        String key = UUID.randomUUID().toString();

        String firstHold = json.writeValueAsString(java.util.Map.of(
                "eventSeatIds", scenario.seatIds().subList(0, 1)));
        JsonNode holdA = postJson("/api/v1/events/" + scenario.eventId() + "/holds", firstHold, token, 201);
        postJson("/api/v1/bookings",
                json.writeValueAsString(java.util.Map.of("holdId", holdA.get("holdId").asText())), token, 201, key);

        String secondHold = json.writeValueAsString(java.util.Map.of(
                "eventSeatIds", scenario.seatIds().subList(1, 2)));
        JsonNode holdB = postJson("/api/v1/events/" + scenario.eventId() + "/holds", secondHold, token, 201);

        // Same key, different hold. Serving the cached response here would
        // silently ignore a real purchase the client asked for.
        mvc.perform(post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                java.util.Map.of("holdId", holdB.get("holdId").asText()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    @DisplayName("A released hold puts the seats straight back on sale")
    void releasingAHoldReturnsTheSeats() throws Exception {
        var scenario = fixtures.scenario(3);
        String tokenA = registerAndGetToken();
        String tokenB = registerAndGetToken();
        String body = json.writeValueAsString(java.util.Map.of(
                "eventSeatIds", scenario.seatIds().subList(0, 1)));

        JsonNode hold = postJson("/api/v1/events/" + scenario.eventId() + "/holds", body, tokenA, 201);

        // While A holds it, B cannot.
        mvc.perform(post("/api/v1/events/" + scenario.eventId() + "/holds")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"));

        mvc.perform(delete("/api/v1/holds/" + hold.get("holdId").asText())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        // Once released, B gets it immediately rather than waiting out the TTL.
        postJson("/api/v1/events/" + scenario.eventId() + "/holds", body, tokenB, 201);
    }

    @Test
    @DisplayName("One user cannot see or cancel another user's hold")
    void holdsArePrivateToTheirOwner() throws Exception {
        var scenario = fixtures.scenario(2);
        String owner = registerAndGetToken();
        String stranger = registerAndGetToken();

        JsonNode hold = postJson("/api/v1/events/" + scenario.eventId() + "/holds",
                json.writeValueAsString(java.util.Map.of("eventSeatIds", scenario.seatIds().subList(0, 1))),
                owner, 201);

        // 404 rather than 403 on purpose: confirming that a hold exists but
        // belongs to someone else is itself a disclosure.
        mvc.perform(get("/api/v1/holds/" + hold.get("holdId").asText())
                        .header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Protected endpoints reject anonymous callers in the standard error shape")
    void anonymousCallersAreRejected() throws Exception {
        mvc.perform(get("/api/v1/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Browsing events needs no token")
    void eventBrowsingIsPublic() throws Exception {
        mvc.perform(get("/api/v1/events")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("An empty seat list is a validation error, not a conflict")
    void validationFailuresAreReportedPerField() throws Exception {
        String token = registerAndGetToken();
        mvc.perform(post("/api/v1/events/1/holds")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventSeatIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("eventSeatIds"));
    }

    // ------------------------------------------------------------------ helpers

    private String registerAndGetToken() throws Exception {
        String email = "api-" + UUID.randomUUID() + "@test.local";
        String body = json.writeValueAsString(java.util.Map.of(
                "email", email, "password", "correct-horse-battery", "fullName", "API Tester"));

        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();

        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private JsonNode postJson(String path, String body, String token, int expectedStatus) throws Exception {
        return postJson(path, body, token, expectedStatus, null);
    }

    private JsonNode postJson(String path, String body, String token, int expectedStatus, String idempotencyKey)
            throws Exception {
        var request = post(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        MvcResult result = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
