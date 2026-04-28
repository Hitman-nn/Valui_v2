package com.valui.user.repository;

import com.valui.common.entity.AuditFallbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditFallbackRepository extends JpaRepository<AuditFallbackEntity, UUID> {
}
