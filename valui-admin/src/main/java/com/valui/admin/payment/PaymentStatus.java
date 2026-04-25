package com.valui.admin.payment;

public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    CANCELLED,
    FAILED;

    /** Maps Yookassa / generic webhook status strings to this enum. */
    public static PaymentStatus fromString(String value) {
        return switch (value == null ? "" : value.toLowerCase()) {
            case "succeeded", "success", "paid" -> SUCCEEDED;
            case "canceled", "cancelled"        -> CANCELLED;
            case "failed", "error"              -> FAILED;
            default                             -> PENDING;
        };
    }
}
