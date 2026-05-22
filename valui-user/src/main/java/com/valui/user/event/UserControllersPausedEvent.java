package com.valui.user.event;

import java.util.List;

/** Один event на всего пользователя: все контроллеры встали на паузу из-за нуля токенов. */
public record UserControllersPausedEvent(Long telegramId, List<Long> chatIds) {}
