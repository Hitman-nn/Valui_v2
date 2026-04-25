package com.valui.common.exception;

public class SubscriptionLimitExceededException extends ValuiException {

    public SubscriptionLimitExceededException(String limitType, int limit) {
        super("Subscription limit exceeded for '%s': max allowed is %d".formatted(limitType, limit), 402);
    }

    public SubscriptionLimitExceededException(String message) {
        super(message, 402);
    }
}
