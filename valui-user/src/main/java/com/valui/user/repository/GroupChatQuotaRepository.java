package com.valui.user.repository;

import com.valui.common.entity.GroupChatQuotaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GroupChatQuotaRepository extends JpaRepository<GroupChatQuotaEntity, Long> {
}
