package com.valui.user.repository;

import com.valui.common.entity.GroupTokenContributionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupTokenContributionRepository extends JpaRepository<GroupTokenContributionEntity, UUID> {

    Optional<GroupTokenContributionEntity> findByChatIdAndUserId(Long chatId, UUID userId);

    List<GroupTokenContributionEntity> findAllByUserId(UUID userId);

    List<GroupTokenContributionEntity> findAllByChatIdAndTokensCommittedGreaterThan(Long chatId, int minTokens);
}
