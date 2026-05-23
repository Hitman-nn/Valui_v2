package com.valui.user.crypto;

import com.valui.common.domain.TokenReasonCode;
import com.valui.common.entity.CryptoInvoiceEntity;
import com.valui.common.entity.TokenExchangeRateEntity;
import com.valui.common.entity.UserEntity;
import com.valui.common.exception.UserNotFoundException;
import com.valui.user.repository.CryptoInvoiceRepository;
import com.valui.user.repository.TokenExchangeRateRepository;
import com.valui.user.repository.UserRepository;
import com.valui.user.service.TokenLedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CryptoPaymentService {

    private static final List<String> SUPPORTED = List.of("USDT", "TON", "ETH", "BTC");
    private static final int EXPIRE_HOURS = 24;

    private final CryptoBotClient          cryptoBotClient;
    private final CryptoInvoiceRepository  invoiceRepository;
    private final TokenExchangeRateRepository rateRepository;
    private final UserRepository           userRepository;
    private final TokenLedgerService       tokenLedgerService;
    private final ApplicationEventPublisher eventPublisher;

    // ─── read ─────────────────────────────────────────────────────────────────

    public List<String> supportedCurrencies() {
        return SUPPORTED;
    }

    /** Возвращает все курсы: currency → tokensPerUnit. */
    @Transactional(readOnly = true)
    public Map<String, BigDecimal> getRates() {
        return rateRepository.findAll().stream()
            .collect(Collectors.toMap(
                TokenExchangeRateEntity::getCurrency,
                TokenExchangeRateEntity::getTokensPerUnit));
    }

    /**
     * Вычисляет сумму в крипте для нужного количества токенов.
     * cryptoAmount = tokenAmount / tokensPerUnit
     */
    public BigDecimal calculateCryptoAmount(int tokenAmount, String currency) {
        BigDecimal rate = rateRepository.findById(currency)
            .map(TokenExchangeRateEntity::getTokensPerUnit)
            .orElseThrow(() -> new IllegalArgumentException("Unknown currency: " + currency));
        return BigDecimal.valueOf(tokenAmount)
            .divide(rate, 8, RoundingMode.CEILING);
    }

    // ─── create invoice ───────────────────────────────────────────────────────

    @Transactional
    public CryptoInvoiceEntity createInvoice(Long telegramId, int tokenAmount, String currency) {
        UserEntity user = userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));

        BigDecimal cryptoAmount = calculateCryptoAmount(tokenAmount, currency);
        String description = tokenAmount + " токенов Valui";
        String payload = "user:" + user.getId() + ":tokens:" + tokenAmount;

        CryptoBotClient.InvoiceResult result =
            cryptoBotClient.createInvoice(currency, cryptoAmount, description, payload);

        CryptoInvoiceEntity invoice = CryptoInvoiceEntity.builder()
            .user(user)
            .invoiceId(result.invoiceId())
            .currency(currency)
            .cryptoAmount(cryptoAmount)
            .tokenAmount(tokenAmount)
            .payUrl(result.botInvoiceUrl())
            .build();
        invoiceRepository.save(invoice);

        log.info("[CRYPTO] Invoice created: userId={} invoiceId={} currency={} cryptoAmount={} tokens={}",
            user.getId(), result.invoiceId(), currency, cryptoAmount, tokenAmount);
        return invoice;
    }

    // ─── poll (called by scheduler) ───────────────────────────────────────────

    @Transactional
    public void processPendingInvoices() {
        List<CryptoInvoiceEntity> pending = invoiceRepository.findAllByStatus("PENDING");
        if (pending.isEmpty()) return;

        // Expire old invoices
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(EXPIRE_HOURS);
        pending.stream()
            .filter(i -> i.getCreatedAt().isBefore(cutoff))
            .forEach(i -> {
                i.setStatus("EXPIRED");
                invoiceRepository.save(i);
                log.info("[CRYPTO] Invoice expired: invoiceId={}", i.getInvoiceId());
            });

        List<CryptoInvoiceEntity> active = pending.stream()
            .filter(i -> i.getCreatedAt().isAfter(cutoff))
            .toList();
        if (active.isEmpty()) return;

        List<Long> ids = active.stream().map(CryptoInvoiceEntity::getInvoiceId).toList();
        List<CryptoBotClient.InvoiceResult> results = cryptoBotClient.getInvoices(ids);

        for (CryptoBotClient.InvoiceResult r : results) {
            if (!"paid".equals(r.status())) continue;

            invoiceRepository.findByInvoiceId(r.invoiceId()).ifPresent(invoice -> {
                if (!"PENDING".equals(invoice.getStatus())) return;

                invoice.setStatus("PAID");
                invoice.setPaidAt(OffsetDateTime.now());
                invoiceRepository.save(invoice);

                tokenLedgerService.credit(
                    invoice.getUser().getId(),
                    invoice.getTokenAmount(),
                    TokenReasonCode.TOPUP,
                    invoice.getId());

                eventPublisher.publishEvent(new CryptoPaymentSuccessEvent(
                    invoice.getUser().getTelegramId(),
                    invoice.getTokenAmount(),
                    invoice.getCurrency(),
                    invoice.getCryptoAmount()));

                log.info("[CRYPTO] Payment confirmed: invoiceId={} userId={} tokens={}",
                    r.invoiceId(), invoice.getUser().getId(), invoice.getTokenAmount());
            });
        }
    }

    // ─── update rate (admin) ──────────────────────────────────────────────────

    @Transactional
    public TokenExchangeRateEntity updateRate(String currency, BigDecimal tokensPerUnit) {
        TokenExchangeRateEntity rate = rateRepository.findById(currency)
            .orElse(TokenExchangeRateEntity.builder().currency(currency).build());
        rate.setTokensPerUnit(tokensPerUnit);
        rate.setUpdatedAt(OffsetDateTime.now());
        return rateRepository.save(rate);
    }
}
