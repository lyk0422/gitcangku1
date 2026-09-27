package com.example.starter.maintenance.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * 隔离状态机领域规则纯单元测试（无 Spring、无数据库）。
 */
class IsolationStateModelTests {

    private static final Instant T = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    void orderStatusPredicates() {
        assertTrue(new IsolationOrder(1, "eq", IsolationOrder.PENDING, "a", T, null, null, null, null)
                .isPending());
        assertTrue(new IsolationOrder(1, "eq", IsolationOrder.EFFECTIVE, "a", T, T, null, null, null)
                .isEffective());
        IsolationOrder released = new IsolationOrder(1, "eq", IsolationOrder.RELEASED, "a", T, T, T, "d", null);
        assertFalse(released.isPending());
        assertFalse(released.isEffective());
    }

    @Test
    void equipmentStatusPredicate() {
        Equipment active = new Equipment("eq", 100, 1, Equipment.ACTIVE, null);
        Equipment dead = new Equipment("eq", 100, 2, Equipment.DEACTIVATED, T);
        assertTrue(active.active());
        assertFalse(dead.active());
    }

    @Test
    void lockPoint_confirmationAndReleaseLifecycle() {
        LockPoint fresh = new LockPoint(1, 0, "L1", "d", null, null, null, null, null, null);
        assertFalse(fresh.confirmedBy(LockPoint.ROLE_ISOLATOR));
        assertFalse(fresh.confirmedBy(LockPoint.ROLE_VERIFIER));
        assertFalse(fresh.fullyConfirmed());
        assertFalse(fresh.released());

        LockPoint half = new LockPoint(1, 0, "L1", "d", "alice", T, null, null, null, null);
        assertTrue(half.confirmedBy(LockPoint.ROLE_ISOLATOR));
        assertFalse(half.confirmedBy(LockPoint.ROLE_VERIFIER));
        assertFalse(half.fullyConfirmed());

        LockPoint confirmed = new LockPoint(1, 0, "L1", "d", "alice", T, "bob", T, null, null);
        assertTrue(confirmed.fullyConfirmed());
        assertFalse(confirmed.released());

        LockPoint released = new LockPoint(1, 0, "L1", "d", "alice", T, "bob", T, "dave", T);
        assertTrue(released.fullyConfirmed());
        assertTrue(released.released());
    }
}
