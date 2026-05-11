package com.valui.admin.payment;

import com.valui.common.entity.PaymentTransactionEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.repository.PaymentTransactionRepository;
import com.valui.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentGateway paymentGateway;
    private final PaymentProperties paymentProperties;
    private final UserService userService;
    private final PaymentTransactionRepository paymentTransactionRepository;

    /**
     * Handles incoming payment webhook — updates transaction status.
     */
    @Transactional
    public void handlePaymentWebhook(String paymentId, PaymentStatus status) {
        PaymentTransactionEntity tx = paymentTransactionRepository.findByPaymentId(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + paymentId));

        String previous = tx.getStatus();
        tx.setStatus(status.name());
        paymentTransactionRepository.save(tx);

        log.info("Payment webhook: paymentId={} status={} (was={})", paymentId, status, previous);
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
