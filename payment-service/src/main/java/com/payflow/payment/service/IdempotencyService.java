package com.payflow.payment.service;

import com.payflow.payment.domain.IdempotencyRecord;
import com.payflow.payment.repo.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;

    public IdempotencyService(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * Inserts a claim row before the payment executes; the primary key is the
     * race arbiter, so a concurrent request with the same key cannot execute
     * twice. Runs in its own transaction so the claim is committed (and
     * visible to competitors) before the payment work starts, and so a key
     * collision does not poison the caller's transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claim(String key, String requestHash) {
        repository.saveAndFlush(new IdempotencyRecord(key, requestHash));
    }

    public Optional<IdempotencyRecord> find(String key) {
        return repository.findById(key);
    }

    @Transactional
    public void complete(String key, int statusCode, String responseBody) {
        IdempotencyRecord record = repository.findById(key).orElseThrow(
                () -> new IllegalStateException("Idempotency claim vanished for key " + key));
        record.complete(statusCode, responseBody);
    }

    /** Releases a claim after a failed execution so the client can retry with the same key. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String key) {
        repository.deleteById(key);
    }
}
