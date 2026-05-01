package com.valui.user.service;

import com.valui.common.entity.DetectedEventEntity;
import com.valui.user.api.DetectedEventPortService;
import com.valui.user.repository.DetectedEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
                                  String title, String url) {
        return repository.insertIfAbsent(id, controllerId, externalId, title, url) > 0;
    }

    @Override
    public long countByControllerId(UUID controllerId) {
        return repository.countByControllerId(controllerId);
    }

    @Override
    public List<String> findExternalIdsByControllerIdSince(UUID controllerId, OffsetDateTime cutoff) {
        return repository.findExternalIdsByControllerIdAndDetectedAtAfter(controllerId, cutoff);
    }

    @Override
    public List<String> findAllExternalIdsByControllerId(UUID controllerId) {
        return repository.findAllExternalIdsByControllerId(controllerId);
    }
}
