package com.valui.user.service;

import com.valui.common.entity.UserEntity;
import com.valui.user.api.UserPortService;
import com.valui.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserPortServiceImpl implements UserPortService {

    private final UserRepository repository;

    @Override
    public Optional<UserEntity> findById(UUID userId) {
        return repository.findById(userId);
    }

    @Override
    public UserEntity getReferenceById(UUID userId) {
        return repository.getReferenceById(userId);
    }
}
