package com.valui.user.crypto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cryptobot.api-token")
public class CryptoPaymentScheduler {

    private final CryptoPaymentService paymentService;

    @Scheduled(fixedDelay = 60_000)
    public void pollPendingInvoices() {
        try {
            paymentService.processPendingInvoices();
        } catch (Exception e) {
            log.error("[CRYPTO] Poll failed", e);
        }
    }
}
