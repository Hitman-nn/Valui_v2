package com.valui.user.repository;

import com.valui.common.entity.TokenActionCostEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TokenActionCostRepository extends JpaRepository<TokenActionCostEntity, String> {
}
