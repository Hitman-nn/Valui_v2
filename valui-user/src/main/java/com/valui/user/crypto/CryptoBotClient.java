package com.valui.user.crypto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Тонкая обёртка над CryptoBot Pay API.
 * Документация: https://help.crypt.bot/crypto-pay-api
 */
@Slf4j
@Component
public class CryptoBotClient {

    private final WebClient client;

    public CryptoBotClient(CryptoBotProperties props) {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
            .responseTimeout(Duration.ofSeconds(15));

        this.client = WebClient.builder()
            .baseUrl(props.apiUrl())
            .defaultHeader("Crypto-Pay-API-Token", props.apiToken())
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .build();
    }

    // ─── DTO ─────────────────────────────────────────────────────────────────

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CryptoBotResponse<T>(boolean ok, T result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvoiceResult(
        @JsonProperty("invoice_id") long invoiceId,
        String asset,
        String amount,
        @JsonProperty("bot_invoice_url") String botInvoiceUrl,
        String status,
        @JsonProperty("paid_at") String paidAt,
        String payload
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InvoiceListResult(
        @JsonProperty("count") int count,
        @JsonProperty("items") List<InvoiceResult> items
    ) {}

    // ─── API calls ────────────────────────────────────────────────────────────

    /** Создаёт инвойс в CryptoBot и возвращает результат. */
    public InvoiceResult createInvoice(String asset, BigDecimal amount,
                                       String description, String payload) {
        var body = Map.of(
            "asset", asset,
            "amount", amount.toPlainString(),
            "description", description,
            "payload", payload
        );
        var resp = client.post()
            .uri("/createInvoice")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(new org.springframework.core.ParameterizedTypeReference<
                CryptoBotResponse<InvoiceResult>>() {})
            .block(Duration.ofSeconds(20));

        if (resp == null || !resp.ok()) {
            throw new IllegalStateException("CryptoBot createInvoice failed");
        }
        log.debug("[CRYPTO] Invoice created: invoiceId={} asset={} amount={}",
            resp.result().invoiceId(), asset, amount);
        return resp.result();
    }

    /** Возвращает список инвойсов по их CryptoBot-ID. */
    public List<InvoiceResult> getInvoices(List<Long> invoiceIds) {
        if (invoiceIds.isEmpty()) return List.of();
        String ids = invoiceIds.stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(","));
        var resp = client.get()
            .uri(u -> u.path("/getInvoices").queryParam("invoice_ids", ids).build())
            .retrieve()
            .bodyToMono(new org.springframework.core.ParameterizedTypeReference<
                CryptoBotResponse<InvoiceListResult>>() {})
            .block(Duration.ofSeconds(20));

        if (resp == null || !resp.ok()) {
            log.warn("[CRYPTO] getInvoices failed for ids={}", ids);
            return List.of();
        }
        return resp.result().items();
    }
}
