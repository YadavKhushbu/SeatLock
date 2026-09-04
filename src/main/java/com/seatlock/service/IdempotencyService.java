package com.seatlock.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.domain.IdempotencyRecord;
import com.seatlock.exception.Exceptions;
import com.seatlock.repo.IdempotencyRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Makes a mutating endpoint safe to retry.
 *
 * <h2>The problem</h2>
 *
 * <p>A client POSTs a booking. The booking commits. The response is lost to a
 * dropped connection. The client, seeing no answer, retries. Without a record of
 * what already happened the second request is indistinguishable from a genuine
 * second purchase, and the user is charged twice for tickets they cannot use.
 * Retries are not an edge case here: mobile networks guarantee them.
 *
 * <h2>The mechanism</h2>
 *
 * <p>Each attempt claims a row keyed on (Idempotency-Key, user). The claim is
 * inserted before the work begins and completed with the response afterwards, so
 * there are three possible states on arrival:
 *
 * <ul>
 *   <li><b>No row</b> — first attempt. Claim it and proceed.</li>
 *   <li><b>COMPLETED row</b> — the work already happened. Replay the stored
 *       response verbatim; do not run the work again.</li>
 *   <li><b>IN_PROGRESS row</b> — a duplicate arrived while the original is still
 *       running. Answer 409 and let the client retry; guessing the outcome would
 *       be worse than admitting we do not know it yet.</li>
 * </ul>
 *
 * <p>The claim runs in its own transaction, committed immediately, because a row
 * that is invisible to concurrent requests until the enclosing transaction ends
 * would defeat the entire purpose. The unique index on (idempotency_key,
 * user_id) settles the case where two duplicates claim at exactly the same
 * moment.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository records;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRecordRepository records, ObjectMapper objectMapper) {
        this.records = records;
        this.objectMapper = objectMapper;
    }

    /** A previously completed response, ready to replay. */
    public record StoredResponse(int status, String body) {
    }

    /**
     * Claims the key for this request.
     *
     * @return the stored response if this request already ran to completion, or
     *         empty if the caller should now do the work
     * @throws Exceptions.IdempotencyKeyReused if the key was used for a different body
     * @throws Exceptions.RequestInFlight      if an identical request is still running
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<StoredResponse> claim(String key, Long userId, Object requestBody) {
        String fingerprint = fingerprint(requestBody);

        Optional<IdempotencyRecord> existing = records.findByIdempotencyKeyAndUserId(key, userId);
        if (existing.isPresent()) {
            return Optional.of(inspect(existing.get(), fingerprint));
        }

        try {
            records.saveAndFlush(IdempotencyRecord.builder()
                    .idempotencyKey(key)
                    .userId(userId)
                    .requestFingerprint(fingerprint)
                    .state(IdempotencyRecord.State.IN_PROGRESS)
                    .build());
            return Optional.empty();
        } catch (DataIntegrityViolationException e) {
            // Two duplicates claimed simultaneously and this one lost. The winner
            // is running the work right now, so this is in-flight by definition.
            log.debug("Idempotency key {} claimed concurrently by another request", key);
            throw new Exceptions.RequestInFlight();
        }
    }

    private StoredResponse inspect(IdempotencyRecord record, String fingerprint) {
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            // Same key, different body. This is a client bug, and serving the old
            // response would hide it while quietly doing the wrong thing.
            throw new Exceptions.IdempotencyKeyReused();
        }
        if (record.getState() == IdempotencyRecord.State.IN_PROGRESS) {
            throw new Exceptions.RequestInFlight();
        }
        return new StoredResponse(record.getResponseStatus(), record.getResponseBody());
    }

    /**
     * Stores the outcome so later retries replay it.
     *
     * <p>Its own transaction again, so the record survives even if the caller's
     * transaction is later rolled back for an unrelated reason.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String key, Long userId, int status, Object responseBody) {
        records.findByIdempotencyKeyAndUserId(key, userId).ifPresent(record -> {
            record.setResponseStatus(status);
            record.setResponseBody(writeJson(responseBody));
            record.setState(IdempotencyRecord.State.COMPLETED);
            records.save(record);
        });
    }

    /**
     * Drops the claim after a failure, so the client can retry the same key.
     *
     * <p>Without this, one transient failure would poison that key forever: every
     * retry would find an IN_PROGRESS row belonging to a request that is never
     * coming back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandon(String key, Long userId) {
        records.findByIdempotencyKeyAndUserId(key, userId)
                .filter(r -> r.getState() == IdempotencyRecord.State.IN_PROGRESS)
                .ifPresent(records::delete);
    }

    public <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is not readable", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response could not be stored for idempotent replay", e);
        }
    }

    /**
     * SHA-256 over the canonical JSON of the request, so that "same key" and
     * "same request" can be told apart.
     */
    private String fingerprint(Object requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(writeJson(requestBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
