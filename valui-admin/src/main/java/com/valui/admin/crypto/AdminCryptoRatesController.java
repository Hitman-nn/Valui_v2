package com.valui.admin.crypto;

import com.valui.common.entity.TokenExchangeRateEntity;
import com.valui.user.crypto.CryptoPaymentService;
import com.valui.user.repository.TokenExchangeRateRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin — Crypto Rates", description = "Курсы обмена крипто → токены")
@RestController
@RequestMapping("/api/v1/admin/crypto-rates")
@RequiredArgsConstructor
@ConditionalOnBean(CryptoPaymentService.class)
public class AdminCryptoRatesController {

    private final CryptoPaymentService       cryptoPaymentService;
    private final TokenExchangeRateRepository rateRepository;

    @GetMapping
    @Operation(summary = "Список всех курсов обмена")
    public List<ExchangeRateDto> list() {
        return rateRepository.findAll().stream()
            .map(r -> new ExchangeRateDto(r.getCurrency(), r.getTokensPerUnit(), r.getUpdatedAt()))
            .toList();
    }

    @PutMapping("/{currency}")
    @Operation(summary = "Обновить курс для валюты")
    public ExchangeRateDto update(
            @PathVariable String currency,
            @Valid @RequestBody UpdateExchangeRateRequest req) {
        TokenExchangeRateEntity rate =
            cryptoPaymentService.updateRate(currency.toUpperCase(), req.tokensPerUnit());
        return new ExchangeRateDto(rate.getCurrency(), rate.getTokensPerUnit(), rate.getUpdatedAt());
    }
}
