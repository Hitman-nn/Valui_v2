package com.valui.user.repository;

import com.valui.common.entity.GlobalFilterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GlobalFilterRepository extends JpaRepository<GlobalFilterEntity, UUID> {

    List<GlobalFilterEntity> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

    long countByUserId(UUID userId);

    Optional<GlobalFilterEntity> findByIdAndUserId(UUID id, UUID userId);

    void deleteByIdAndUserId(UUID id, UUID userId);
}
