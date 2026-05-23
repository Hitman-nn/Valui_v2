package com.valui.user.crypto;

import com.valui.common.domain.CryptoInvoiceStatus;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
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

    private final CryptoBotClient            cryptoBotClient;
    private final CryptoInvoiceRepository    invoiceRepository;
    private final TokenExchangeRateRepository rateRepository;
    private final UserRepository             userRepository;
    private final TokenLedgerService         tokenLedgerService;
    private final ApplicationEventPublisher  eventPublisher;

    // Self-injection for transaction splitting in processPendingInvoices
    @Lazy @Autowired
    private CryptoPaymentService self;

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

    /**
     * Создаёт новый инвойс или возвращает существующий PENDING-инвойс
     * для той же валюты (идемпотентность при повторном нажатии).
     */
    @Transactional
    public CryptoInvoiceEntity createInvoice(Long telegramId, int tokenAmount, String currency) {
        UserEntity user = userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new UserNotFoundException(telegramId));

        // Return existing PENDING invoice for same currency if amount matches
        var existing = invoiceRepository.findFirstByUserIdAndCurrencyAndStatus(
            user.getId(), currency, CryptoInvoiceStatus.PENDING);
        if (existing.isPresent() && existing.get().getTokenAmount().equals(tokenAmount)) {
            log.info("[CRYPTO] Reusing existing invoice: invoiceId={} userId={}",
                existing.get().getInvoiceId(), user.getId());
            return existing.get();
        }

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

    /**
     * Координатор: разбит на отдельные транзакции, чтобы HTTP-вызов к CryptoBot
     * не держал DB-соединение открытым.
     */
    public void processPendingInvoices() {
        List<Long> activeIds = self.expireOldAndLoadActiveIds();
        if (activeIds.isEmpty()) return;

        List<CryptoBotClient.InvoiceResult> results = cryptoBotClient.getInvoices(activeIds);

        for (CryptoBotClient.InvoiceResult r : results) {
            if ("paid".equals(r.status())) {
                self.confirmPayment(r.invoiceId());
            }
        }
    }

    /** Экспайрит старые инвойсы и возвращает ID активных. Отдельная транзакция. */
    @Transactional
    public List<Long> expireOldAndLoadActiveIds() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(EXPIRE_HOURS);
        int expired = invoiceRepository.expireOldInvoices(
            CryptoInvoiceStatus.PENDING, CryptoInvoiceStatus.EXPIRED, cutoff);
        if (expired > 0) log.info("[CRYPTO] Expired {} old invoices", expired);

        return invoiceRepository.findAllByStatus(CryptoInvoiceStatus.PENDING).stream()
            .map(CryptoInvoiceEntity::getInvoiceId)
            .toList();
    }

    /** Подтверждает оплату одного инвойса. Отдельная транзакция. */
    @Transactional
    public void confirmPayment(long invoiceId) {
        var opt = invoiceRepository.findByInvoiceId(invoiceId);
        if (opt.isEmpty()) {
            log.warn("[CRYPTO] Invoice not found for confirmation: invoiceId={}", invoiceId);
            return;
        }
        var invoice = opt.get();
        if (invoice.getStatus() != CryptoInvoiceStatus.PENDING) return;

        invoice.setStatus(CryptoInvoiceStatus.PAID);
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
            invoiceId, invoice.getUser().getId(), invoice.getTokenAmount());
    }

    // ─── update rate (admin) ──────────────────────────────────────────────────

    @Transactional
    public TokenExchangeRateEntity updateRate(String currency, BigDecimal tokensPerUnit) {
        TokenExchangeRateEntity rate = rateRepository.findById(currency)
            .orElseGet(() -> TokenExchangeRateEntity.builder().currency(currency).build());
        rate.setTokensPerUnit(tokensPerUnit);
        rate.setUpdatedAt(OffsetDateTime.now());
        return rateRepository.save(rate);
    }
}
