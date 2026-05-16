package com.valui.common.domain;

public enum SlipResult {
    OPEN,
    WON,
    LOST,
    RETURNED,
    /** Express leg left undetermined because another leg already lost the bet. Purely informational. */
    VOID
}
