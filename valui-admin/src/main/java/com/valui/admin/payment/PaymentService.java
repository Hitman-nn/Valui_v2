package com.valui.admin.payment;

import com.valui.common.entity.PaymentTransactionEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.repository.PaymentTransactionRepository;
import com.valui.user.repository.SubscriptionPlanRepository;
import com.valui.user.service.SubscriptionService;
import com.valui.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentGateway paymentGateway;
    private final PaymentProperties paymentProperties;
    private final UserService userService;
    private final SubscriptionService subscriptionService;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

    /**
     * Initiates a payment for the given plan.
     * Persists a PENDING {@link PaymentTransactionEntity} and returns the confirmation URL.
     */
    @Transactional
    public PaymentResponse initiateSubscriptionUpgrade(Long telegramId, String planCode) {
        UserEntity user = userService.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));

        var plan = subscriptionPlanRepository.findByCode(planCode)
            .orElseThrow(() -> new IllegalArgumentException("Plan not found: " + planCode));

        if (plan.getPriceRub() == null || plan.getPriceRub().compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("FREE plan does not require payment");
        }

        CreatePaymentRequest request = new CreatePaymentRequest(
            user.getId(),
            telegramId,
            planCode,
            plan.getPriceRub(),
            "RUB",
            "Подписка Valui " + plan.getName(),
            "https://t.me/ValuiBot"
        );

        PaymentResponse response = paymentGateway.createPayment(request);

        PaymentTransactionEntity tx = PaymentTransactionEntity.builder()
            .user(user)
            .paymentId(response.paymentId())
            .planCode(planCode)
            .amount(plan.getPriceRub())
            .currency("RUB")
            .status(response.status().name())
            .description(request.description())
            .confirmationUrl(response.confirmationUrl())
            .gateway(paymentProperties.isStub() ? "stub" : "yookassa")
            .build();
        paymentTransactionRepository.save(tx);

        log.info("Payment initiated: telegramId={} plan={} paymentId={} amount={}",
            telegramId, planCode, response.paymentId(), plan.getPriceRub());
        return response;
    }

    /**
     * Handles incoming payment webhook.
     * On SUCCEEDED: activates the purchased subscription plan.
     */
    @Transactional
    public void handlePaymentWebhook(String paymentId, PaymentStatus status) {
        PaymentTransactionEntity tx = paymentTransactionRepository.findByPaymentId(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + paymentId));

        String previous = tx.getStatus();
        tx.setStatus(status.name());
        paymentTransactionRepository.save(tx);

        log.info("Payment webhook: paymentId={} status={} (was={})", paymentId, status, previous);

        if (status == PaymentStatus.SUCCEEDED) {
            subscriptionService.activatePlan(tx.getUser().getId(), tx.getPlanCode(), paymentId);
        }
    }

    /** Returns all payment transactions for the user, newest first. */
    public List<PaymentHistoryDto> getPaymentHistory(Long telegramId) {
        UserEntity user = userService.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));

        return paymentTransactionRepository
            .findAllByUserId(user.getId())
            .stream()
            .map(PaymentHistoryDto::from)
            .toList();
    }
}
