package com.valui.admin.events;

import com.valui.user.repository.DetectedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class DetectedEventCleanupService {

    private final DetectedEventRepository repository;

    /**
     * Deletes up to {@code batchSize} expired rows in its own transaction.
     * Called in a loop from the controller so each batch commits independently,
     * releasing row locks before the next batch begins.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredBatch(OffsetDateTime threshold, int batchSize) {
        return repository.deleteExpiredBatch(threshold, batchSize);
    }
}
