package com.valui.user.api;

import com.valui.common.domain.BookmakerType;
import com.valui.common.entity.ControllerEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Port interface: stable contract for controller data access, owned by valui-user.
 * Consumed by valui-monitor; hides JPA repository details behind a versioned API.
 */
public interface ControllerPortService {

    List<ControllerEntity> findAllActive();

    List<ControllerEntity> findAllActiveByUserId(UUID userId);

    Optional<ControllerEntity> findById(UUID controllerId);

    Optional<ControllerEntity> findByIdAndUserId(UUID controllerId, UUID userId);

    ControllerEntity save(ControllerEntity entity);

    void updateLastCheckedAt(UUID controllerId, OffsetDateTime checkedAt);

    void updateIsActive(UUID controllerId, boolean active);

    boolean existsByUserAndBookmakerAndUrl(UUID userId, BookmakerType bookmaker, String url);

    Page<ControllerEntity> findByUserIdPageable(UUID userId, Pageable pageable);

    List<ControllerEntity> findAllActiveByNotificationChatId(Long chatId);
}
