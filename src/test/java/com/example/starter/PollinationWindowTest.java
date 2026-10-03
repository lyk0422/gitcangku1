package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.domain.PollinationWindow;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 授粉窗口边界纯单元测试：10:00 开放、24 小时窗口，
 * 次日 09:59 可操作，次日 10:00 起拒绝（左闭右开）。
 */
class PollinationWindowTest {

    private static final Instant OPEN = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant CLOSE = Instant.parse("2026-10-05T10:00:00Z");

    private final PollinationWindow window = new PollinationWindow(OPEN, CLOSE);

    @Test
    void openMomentIsIncluded() {
        assertTrue(window.contains(OPEN));
    }

    @Test
    void beforeOpenIsRejected() {
        assertFalse(window.contains(Instant.parse("2026-10-04T09:59:59Z")));
    }

    @Test
    void nextDay0959IsIncluded() {
        assertTrue(window.contains(Instant.parse("2026-10-05T09:59:00Z")));
        assertTrue(window.contains(Instant.parse("2026-10-05T09:59:59Z")));
    }

    @Test
    void closeMomentIsExcluded() {
        assertFalse(window.contains(CLOSE));
        assertTrue(window.isExpiredAt(CLOSE));
    }

    @Test
    void afterCloseIsExpired() {
        assertFalse(window.contains(Instant.parse("2026-10-05T10:00:01Z")));
        assertTrue(window.isExpiredAt(Instant.parse("2026-10-06T10:00:00Z")));
    }
}