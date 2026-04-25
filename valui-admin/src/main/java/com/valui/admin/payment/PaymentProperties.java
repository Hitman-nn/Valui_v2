package com.valui.admin.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(
    /** Active gateway: "stub" or "yookassa". Defaults to "stub". */
    String gateway,

    YookassaProperties yookassa
) {
    public boolean isStub() {
        return gateway == null || "stub".equalsIgnoreCase(gateway);
    }

    @ConfigurationProperties(prefix = "payment.yookassa")
    public record YookassaProperties(
        String shopId,
        String secretKey,
        String apiUrl
    ) {}
}
