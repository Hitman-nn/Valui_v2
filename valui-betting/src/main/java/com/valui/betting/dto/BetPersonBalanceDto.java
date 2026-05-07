package com.valui.betting.dto;

import com.valui.common.entity.BetPersonBalanceEntity;

import java.math.BigDecimal;
import java.util.UUID;

/** Balance of a person within an account. balance==null means the person is not linked to the account. */
public record BetPersonBalanceDto(UUID personId, String personName, BigDecimal balance) {
    public boolean isLinked() { return balance != null; }

    public static BetPersonBalanceDto from(BetPersonBalanceEntity e) {
        return new BetPersonBalanceDto(e.getPerson().getId(), e.getPerson().getDisplayName(), e.getBalance());
    }

    public static BetPersonBalanceDto notLinked(UUID personId, String personName) {
        return new BetPersonBalanceDto(personId, personName, null);
    }
}
