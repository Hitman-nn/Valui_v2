package com.valui.admin.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookSignatureService {

    private final PaymentProperties paymentProperties;

    /**
     * Validates the webhook HMAC-SHA256 signature.
     * For non-Yookassa gateways (e.g. stub) validation is skipped — returns {@code true}.
     *
     * @param rawBody   raw request body (must not be pre-parsed)
     * @param signature value from {@code X-Yookassa-Signature} header
     */
    public boolean isValid(String rawBody, String signature) {
        if (paymentProperties.isStub()) {
            log.debug("Webhook signature validation skipped for stub gateway");
            return true;
        }

        if (signature == null || signature.isBlank()) {
            log.warn("Webhook received without signature header");
            return false;
        }

        String secretKey = paymentProperties.yookassa().secretKey();
        if (secretKey == null || secretKey.isBlank()) {
            log.error("Yookassa secret key not configured — cannot validate webhook signature");
            return false;
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(hmac);
            // Constant-time comparison to prevent timing attacks
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            log.error("HMAC validation error", e);
            return false;
        }
    }
}
