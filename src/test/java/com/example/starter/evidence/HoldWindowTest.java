package com.example.starter.evidence;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结有效期左闭右开边界的纯单元测试，不依赖 Spring 与数据库。
 */
class HoldWindowTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 1, 10, 0, 0);
    private static final LocalDateTime EXPIRES = LocalDateTime.of(2026, 10, 1, 12, 0, 0);

    @Test
    void withinWindowIsEffective() {
        assertThat(HoldWindow.isEffective(HoldStatus.ACTIVE, CREATED, EXPIRES,
                LocalDateTime.of(2026, 10, 1, 11, 59, 59))).isTrue();
    }

    @Test
    void leftEndpointCreatedMomentIsInclusive() {
        assertThat(HoldWindow.isEffective(HoldStatus.ACTIVE, CREATED, EXPIRES, CREATED)).isTrue();
    }

    @Test
    void rightEndpointExpiryMomentIsExclusive() {
        // 恰好到期：12:00:00 不再阻断新销毁申请
        assertThat(HoldWindow.isEffective(HoldStatus.ACTIVE, CREATED, EXPIRES, EXPIRES)).isFalse();
    }

    @Test
    void afterExpiryIsIneffective() {
        assertThat(HoldWindow.isEffective(HoldStatus.ACTIVE, CREATED, EXPIRES,
                EXPIRES.plusSeconds(1))).isFalse();
    }

    @Test
    void beforeCreationIsIneffective() {
        assertThat(HoldWindow.isEffective(HoldStatus.ACTIVE, CREATED, EXPIRES,
                CREATED.minusSeconds(1))).isFalse();
    }

    @Test
    void releasedHoldIsNeverEffectiveEvenWithinWindow() {
        assertThat(HoldWindow.isEffective(HoldStatus.RELEASED, CREATED, EXPIRES,
                LocalDateTime.of(2026, 10, 1, 11, 0, 0))).isFalse();
    }
}
