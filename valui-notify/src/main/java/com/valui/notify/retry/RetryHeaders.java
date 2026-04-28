package com.valui.notify.retry;

/** Kafka record header keys for the retry / DLQ pipeline. */
public final class RetryHeaders {

    public static final String RETRY_COUNT    = "X-Retry-Count";
    public static final String ORIGINAL_TOPIC = "X-Original-Topic";
    public static final String ERROR_MESSAGE  = "X-Error-Message";
    public static final String FAILED_AT      = "X-Failed-At";

    private RetryHeaders() {}
}
