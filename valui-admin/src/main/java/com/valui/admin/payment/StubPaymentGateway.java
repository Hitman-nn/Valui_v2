package com.valui.admin.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * In-process stub gateway — active when {@code payment.gateway=stub} (default).
 * All calls are logged; checkPaymentStatus always returns SUCCEEDED for easy test automation.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.gateway", havingValue = "stub", matchIfMissing = true)
public class StubPaymentGateway implements PaymentGateway {

    @Override
    public PaymentResponse createPayment(CreatePaymentRequest request) {
        String paymentId = UUID.randomUUID().toString();
        String confirmationUrl = "http://localhost/pay/" + paymentId;

        log.info("[STUB] createPayment: userId={} telegramId={} plan={} amount={} currency={} → paymentId={}",
            request.userId(), request.telegramId(), request.planCode(),
            request.amount(), request.currency(), paymentId);

        return new PaymentResponse(paymentId, confirmationUrl, PaymentStatus.PENDING, request.amount());
    }

    @Override
    public PaymentStatus checkPaymentStatus(String paymentId) {
        log.info("[STUB] checkPaymentStatus: paymentId={} → SUCCEEDED", paymentId);
        return PaymentStatus.SUCCEEDED;
    }

    @Override
    public void cancelPayment(String paymentId) {
        log.info("[STUB] cancelPayment: paymentId={}", paymentId);
    }
}
