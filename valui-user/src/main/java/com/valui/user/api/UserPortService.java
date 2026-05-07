package com.valui.user.api;

import com.valui.common.entity.UserEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * Port interface: stable contract for user data access, owned by valui-user.
 * Consumed by valui-notify; hides JPA repository details behind a versioned API.
 */
public interface UserPortService {

    Optional<UserEntity> findById(UUID userId);

    /**
     * Returns a JPA proxy reference. Must only be used to establish a FK association
     * on a new entity within an active transaction — never dereference the proxy.
     */
    UserEntity getReferenceById(UUID userId);
}
