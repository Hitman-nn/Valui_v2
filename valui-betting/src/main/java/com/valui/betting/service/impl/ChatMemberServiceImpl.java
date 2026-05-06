package com.valui.betting.service.impl;

import com.valui.betting.repository.ChatMemberRepository;
import com.valui.betting.service.ChatMemberService;
import com.valui.common.entity.ChatMemberEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMemberServiceImpl implements ChatMemberService {

    private final ChatMemberRepository repo;

    @Override
    @Transactional
    public void track(long chatId, long telegramId, String firstName, String username) {
        try {
            repo.upsert(chatId, telegramId, firstName, username, OffsetDateTime.now());
        } catch (Exception e) {
            log.debug("[CHAT-MEMBER] Tracking failed chatId={} telegramId={}: {}", chatId, telegramId, e.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMemberEntity> getMembers(long chatId) {
        return repo.findAllByChatIdOrderByFirstNameAsc(chatId);
    }
}
