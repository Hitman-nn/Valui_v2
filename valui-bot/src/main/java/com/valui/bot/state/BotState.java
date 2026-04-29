package com.valui.bot.state;

public enum BotState {
    IDLE,
    WAITING_FILTER_RULE,
    WAITING_CONTROLLER_URL,
    WAITING_CONFIRM_DELETE,
    WAITING_CONFIRM_CREATE,
    SELECTING_BOOKMAKER,
    SELECTING_SPORT,
    SELECTING_TOURNAMENT,
    @Deprecated // удалён вместе с group-boost системой
    WAITING_BOOST_AMOUNT
}
