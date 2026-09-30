package com.example.starter.evidence;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结有效期判定单元测试：UTC 窗口左闭右开 [创建起, expiresAt)，
 * 恰好到期不再阻断；已解除冻结不再有效。不依赖 Spring 与数据库。
 */
class RetentionHoldTest {

    private static final LocalDateTime EXPIRES = LocalDateTime.of(2026, 10, 1, 12, 0, 0);

    private RetentionHold hold(HoldStatus status) {
        return new RetentionHold(1L, "HOLD-1", HoldLevel.CASE, "CASE-K", "CASE-K", null,
                EXPIRES, "诉讼保留", "bob", status,
                LocalDateTime.of(2026, 10, 1, 11, 0, 0),
                null, null, null, null);
    }

    @Test
    void activeHoldEffectiveBeforeExpiry() {
        RetentionHold hold = hold(HoldStatus.ACTIVE);
        assertThat(hold.effectiveAt(EXPIRES.minusSeconds(1))).isTrue();
        assertThat(hold.effectiveAt(EXPIRES.minusNanos(1))).isTrue();
    }

    @Test
    void activeHoldNotEffectiveExactlyAtExpiry() {
        // 左闭右开：now == expiresAt 时已自然到期，不再阻断新申请。
        assertThat(hold(HoldStatus.ACTIVE).effectiveAt(EXPIRES)).isFalse();
        assertThat(hold(HoldStatus.ACTIVE).effectiveAt(EXPIRES.plusSeconds(1))).isFalse();
    }

    @Test
    void releasedHoldNeverEffectiveEvenBeforeExpiry() {
        RetentionHold released = new RetentionHold(1L, "HOLD-1", HoldLevel.EVIDENCE, "CASE-K",
                "EV-A", "EV-A", EXPIRES, "诉讼保留", "bob", HoldStatus.RELEASED,
                LocalDateTime.of(2026, 10, 1, 11, 0, 0),
                "carol", "案件结案", "REL-1",
                LocalDateTime.of(2026, 10, 1, 11, 30, 0));
        assertThat(released.effectiveAt(EXPIRES.minusSeconds(1))).isFalse();
        assertThat(released.effectiveAt(EXPIRES)).isFalse();
    }
}
