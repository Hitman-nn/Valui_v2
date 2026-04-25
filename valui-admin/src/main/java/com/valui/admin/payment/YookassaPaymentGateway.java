package com.valui.admin.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Yookassa payment gateway integration.
 *
 * <p>Activated via {@code payment.gateway=yookassa}.
 * Requires {@code payment.yookassa.shop-id} and {@code payment.yookassa.secret-key} to be set.
 *
 * <p>TODO: Implement using Yookassa REST API v3
 * <ul>
 *   <li>Docs: https://yookassa.ru/developers/api</li>
 *   <li>Auth: Basic auth (shopId:secretKey)</li>
 *   <li>POST /v3/payments — create payment</li>
 *   <li>GET  /v3/payments/{id} — check status</li>
 *   <li>POST /v3/payments/{id}/cancel — cancel</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.gateway", havingValue = "yookassa")
public class YookassaPaymentGateway implements PaymentGateway {

    private final PaymentProperties paymentProperties;

    @Override
    public PaymentResponse createPayment(CreatePaymentRequest request) {
        throw new UnsupportedOperationException("Yookassa integration not yet implemented");
    }

    @Override
    public PaymentStatus checkPaymentStatus(String paymentId) {
        throw new UnsupportedOperationException("Yookassa integration not yet implemented");
    }

    @Override
    public void cancelPayment(String paymentId) {
        throw new UnsupportedOperationException("Yookassa integration not yet implemented");
    }
}
