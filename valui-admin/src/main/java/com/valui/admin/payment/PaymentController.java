package com.valui.admin.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.valui.admin.security.CurrentUser;
import com.valui.admin.security.ValuiPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@Tag(name = "Payment", description = "Subscription payment and webhook endpoints")
@RestController
@RequestMapping("/api/v1/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final WebhookSignatureService signatureService;
    private final ObjectMapper objectMapper;   // @Primary (snake_case) — Yookassa uses snake_case too

    // ─── public: webhook (no JWT required, validated via HMAC) ───────────────

    @PostMapping("/webhook")
    @Operation(summary = "Payment provider webhook — validates HMAC-SHA256 signature")
    public ResponseEntity<Void> webhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Yookassa-Signature", required = false) String signature) {

        if (!signatureService.isValid(rawBody, signature)) {
            log.warn("Webhook rejected: invalid or missing signature");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            WebhookRequest request = objectMapper.readValue(rawBody, WebhookRequest.class);
            if (request.object() == null || request.object().id() == null) {
                log.warn("Webhook payload missing object.id");
                return ResponseEntity.badRequest().build();
            }

            PaymentStatus status = PaymentStatus.fromString(request.object().status());
            paymentService.handlePaymentWebhook(request.object().id(), status);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to process webhook payload", e);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build();
        }
    }

    // ─── authenticated: initiate + history ───────────────────────────────────

    @PostMapping("/initiate")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Initiate a subscription upgrade payment")
    public ResponseEntity<PaymentResponse> initiatePayment(
            @CurrentUser ValuiPrincipal principal,
            @RequestParam @NotBlank String planCode) {
        return ResponseEntity.ok(
            paymentService.initiateSubscriptionUpgrade(principal.telegramId(), planCode)
        );
    }

    @GetMapping("/history")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Get payment history for the current user")
    public ResponseEntity<List<PaymentHistoryDto>> getHistory(@CurrentUser ValuiPrincipal principal) {
        return ResponseEntity.ok(paymentService.getPaymentHistory(principal.telegramId()));
    }
}
