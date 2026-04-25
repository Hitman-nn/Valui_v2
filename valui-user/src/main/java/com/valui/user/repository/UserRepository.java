package com.valui.user.repository;

import com.valui.common.domain.UserStatus;
import com.valui.common.entity.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByTelegramId(Long telegramId);

    boolean existsByTelegramId(Long telegramId);

    List<UserEntity> findAllByStatus(UserStatus status);

    Page<UserEntity> findAllByStatus(UserStatus status, Pageable pageable);

    long countByStatus(UserStatus status);

    default Page<UserEntity> findAllBannedUsers(Pageable pageable) {
        return findAllByStatus(UserStatus.BANNED, pageable);
    }

    default long countActiveUsers() {
        return countByStatus(UserStatus.ACTIVE);
    }

    @Modifying
    @Query("UPDATE UserEntity u SET u.status = :status WHERE u.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") UserStatus status);
}
