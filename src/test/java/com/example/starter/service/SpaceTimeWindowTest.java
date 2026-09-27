package com.example.starter.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 左闭右开时空窗合法性单元测试。
 */
class SpaceTimeWindowTest {

    @Test
    void validWindowAccepted() {
        SpaceTimeWindow w = new SpaceTimeWindow(100L, 200L, 0, 1000);
        assertEquals(100L, w.startMs());
        assertEquals(1000, w.altMax());
    }

    @Test
    void endMustBeAfterStart() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpaceTimeWindow(200L, 200L, 0, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new SpaceTimeWindow(300L, 200L, 0, 1000));
    }

    @Test
    void altitudeMustBeHalfOpenNonEmpty() {
        assertThrows(IllegalArgumentException.class,
                () -> new SpaceTimeWindow(100L, 200L, 500, 500));
        assertThrows(IllegalArgumentException.class,
                () -> new SpaceTimeWindow(100L, 200L, 600, 500));
    }
}
