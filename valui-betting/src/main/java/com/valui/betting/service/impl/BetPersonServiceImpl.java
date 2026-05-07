package com.valui.betting.service.impl;

import com.valui.betting.dto.BetPersonDto;
import com.valui.betting.repository.BetPersonRepository;
import com.valui.betting.service.BetPersonService;
import com.valui.common.entity.BetPersonEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BetPersonServiceImpl implements BetPersonService {

    private final BetPersonRepository repo;

    @Override
    @Transactional
    public BetPersonDto create(long chatId, String displayName) {
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("Имя участника не может быть пустым");
        if (repo.existsByChatIdAndDisplayNameIgnoreCase(chatId, displayName.trim())) {
            throw new IllegalStateException("Участник с таким именем уже существует");
        }
        BetPersonEntity entity = BetPersonEntity.builder()
                .chatId(chatId)
                .displayName(displayName.trim())
                .createdAt(OffsetDateTime.now())
                .build();
        return BetPersonDto.from(repo.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BetPersonDto> listForChat(long chatId) {
        return repo.findAllByChatIdOrderByDisplayNameAsc(chatId)
                .stream().map(BetPersonDto::from).toList();
    }

    @Override
    @Transactional
    public void delete(UUID personId, long chatId) {
        BetPersonEntity e = repo.findById(personId)
                .orElseThrow(() -> new NoSuchElementException("Участник не найден"));
        if (!e.getChatId().equals(chatId)) throw new SecurityException("Участник не принадлежит этому чату");
        repo.delete(e);
    }
}
