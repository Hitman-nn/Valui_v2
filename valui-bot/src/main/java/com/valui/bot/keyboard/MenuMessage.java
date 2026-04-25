package com.valui.bot.keyboard;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

public record MenuMessage(String text, InlineKeyboardMarkup keyboard) {}
