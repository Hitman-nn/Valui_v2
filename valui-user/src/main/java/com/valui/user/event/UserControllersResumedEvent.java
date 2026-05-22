package com.valui.user.event;

import java.util.List;

/** Один event на всего пользователя: контроллеры восстановлены после пополнения токенов. */
public record UserControllersResumedEvent(Long telegramId, List<Long> chatIds) {}
