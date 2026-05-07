package com.valui.betting.service;

import com.valui.betting.dto.BetPersonDto;

import java.util.List;
import java.util.UUID;

public interface BetPersonService {
    BetPersonDto create(long chatId, String displayName);
    List<BetPersonDto> listForChat(long chatId);
    void delete(UUID personId, long chatId);
}
