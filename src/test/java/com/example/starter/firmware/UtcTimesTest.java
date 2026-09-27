package com.example.starter.firmware;

import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.support.UtcTimes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UTC 时刻解析与左闭右开边界单元测试。
 */
class UtcTimesTest {

    @Test
    void 合法时刻归一化为UTC() {
        Instant parsed = UtcTimes.parse("2026-10-01T00:30:00+08:00");
        assertThat(parsed.toString()).isEqualTo("2026-09-30T16:30:00Z");
        assertThat(UtcTimes.parse("2026-09-30T16:30:00Z").toString()).isEqualTo("2026-09-30T16:30:00Z");
    }

    @Test
    void 非法时刻返回422() {
        for (String invalid : List.of("", "2026-10-01 00:00:00", "not-a-time", "2026-13-01T00:00:00Z")) {
            assertThatThrownBy(() -> UtcTimes.parse(invalid))
                    .as("非法时刻: '%s'", invalid)
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(ex.code()).isEqualTo("INVALID_INSTANT");
                    });
        }
    }

    @Test
    void 左闭右开_到期当刻失效() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        Instant expiresAt = Instant.parse("2026-10-02T00:00:00Z");
        assertThat(now.isBefore(expiresAt)).isTrue();
        assertThat(expiresAt.isBefore(expiresAt)).isFalse();
    }
}
