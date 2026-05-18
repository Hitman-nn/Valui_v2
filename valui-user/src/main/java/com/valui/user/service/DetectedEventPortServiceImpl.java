package com.valui.user.service;

import com.valui.common.entity.DetectedEventEntity;
import com.valui.user.api.DetectedEventPortService;
import com.valui.user.repository.DetectedEventRepository;
import com.valui.user.repository.DetectedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DetectedEventPortServiceImpl implements DetectedEventPortService {

    private final DetectedEventRepository repository;

    @Override
    @Transactional
    public DetectedEventEntity save(DetectedEventEntity entity) {
        return repository.save(entity);
    }

    @Override
    @Transactional
    public boolean insertIfAbsent(UUID id, UUID controllerId, String externalId,
                                  String title, String url, String extraData,
                                  OffsetDateTime expiresAt) {
        return repository.insertIfAbsent(id, controllerId, externalId, title, url, extraData, expiresAt) > 0;
    }

    @Override
    public long countByControllerId(UUID controllerId) {
        return repository.countByControllerId(controllerId);
    }

    @Override
    public Optional<UUID> findIdByControllerIdAndExternalId(UUID controllerId, String externalId) {
        return repository.findByControllerIdAndEventExternalId(controllerId, externalId)
                .map(DetectedEventEntity::getId);
    }

    @Override
    public DetectedEventEntity getReferenceById(UUID id) {
        return repository.getReferenceById(id);
    }

    @Override
    public Map<UUID, Long> countByControllerIdIn(Collection<UUID> controllerIds) {
        if (controllerIds == null || controllerIds.isEmpty()) return Map.of();
        return repository.countByControllerIdIn(controllerIds).stream()
                .collect(Collectors.toMap(
                        DetectedEventRepository.ControllerEventCount::getControllerId,
                        DetectedEventRepository.ControllerEventCount::getEventCount));
    }

    @Override
    public List<String> findExternalIdsByControllerIdSince(UUID controllerId, OffsetDateTime cutoff) {
        return repository.findExternalIdsByControllerIdAndDetectedAtAfter(controllerId, cutoff);
    }

    @Override
    public List<String> findAllExternalIdsByControllerId(UUID controllerId) {
        return repository.findAllExternalIdsByControllerId(controllerId);
    }

    @Override
    @Transactional
    public int deleteExpiredBatch(OffsetDateTime threshold, int batchSize) {
        return repository.deleteExpiredBatch(threshold, batchSize);
    }
}
