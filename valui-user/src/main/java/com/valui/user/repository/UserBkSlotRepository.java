package com.valui.user.repository;

import com.valui.common.entity.UserBkSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserBkSlotRepository extends JpaRepository<UserBkSlotEntity, UUID> {

    Optional<UserBkSlotEntity> findByUserIdAndBookmaker(UUID userId, String bookmaker);

    List<UserBkSlotEntity> findAllByUserId(UUID userId);
}
