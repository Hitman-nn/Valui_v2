package com.valui.admin.payment;

/**
 * Abstraction over payment providers (stub, Yookassa, etc.).
 * Active implementation is selected via {@code payment.gateway} property.
 */
public interface PaymentGateway {

    PaymentResponse createPayment(CreatePaymentRequest request);

    PaymentStatus checkPaymentStatus(String paymentId);

    void cancelPayment(String paymentId);
}
