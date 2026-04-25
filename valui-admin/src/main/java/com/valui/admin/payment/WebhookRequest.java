package com.valui.admin.payment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Webhook payload sent by the payment provider (Yookassa-compatible format). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookRequest(
    String type,
    String event,
    WebhookObject object
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookObject(String id, String status) {}
}
