package com.valui.user.repository;

import com.valui.common.entity.TokenExchangeRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TokenExchangeRateRepository extends JpaRepository<TokenExchangeRateEntity, String> {}
