package com.valui.user.repository;

import com.valui.common.entity.TokenPackEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TokenPackRepository extends JpaRepository<TokenPackEntity, UUID> {

    List<TokenPackEntity> findAllByIsActiveTrueOrderBySortOrderAsc();
}
