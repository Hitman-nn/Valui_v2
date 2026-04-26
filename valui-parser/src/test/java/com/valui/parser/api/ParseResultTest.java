package com.valui.parser.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParseResultTest {

    @Test
    void ok_setsAllFields() {
        var before = Instant.now();
        ParseResult<List<String>> result = ParseResult.ok(List.of("a", "b"), 42L);
        var after = Instant.now();

        assertThat(result.success()).isTrue();
        assertThat(result.data()).containsExactly("a", "b");
        assertThat(result.errorMessage()).isNull();
        assertThat(result.latencyMs()).isEqualTo(42L);
        assertThat(result.fetchedAt()).isBetween(before, after);
    }

    @Test
    void error_setsAllFields() {
        var before = Instant.now();
        ParseResult<List<String>> result = ParseResult.error("network timeout");
        var after = Instant.now();

        assertThat(result.success()).isFalse();
        assertThat(result.data()).isNull();
        assertThat(result.errorMessage()).isEqualTo("network timeout");
        assertThat(result.latencyMs()).isZero();
        assertThat(result.fetchedAt()).isBetween(before, after);
    }

    @Test
    void ok_withNullData_stillSuccess() {
        ParseResult<String> result = ParseResult.ok(null, 0L);
        assertThat(result.success()).isTrue();
        assertThat(result.data()).isNull();
    }
}
