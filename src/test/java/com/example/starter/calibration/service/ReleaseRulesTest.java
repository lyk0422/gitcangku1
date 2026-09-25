package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 放行共享校验规则 {@link ReleaseRules} 的纯单元测试（不依赖 Spring 与数据库），
 * 验证同仪器批量放行与跨仪器联合批次共享的单项判定原因码。
 */
class ReleaseRulesTest {

    private Measurement measurement(MeasurementStatus status, boolean passed, String submittedBy) {
        return new Measurement(1L, "K-1", "INS-1", Instant.parse("2026-06-01T00:00:00Z"),
                BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("9"), submittedBy,
                7L, new BigDecimal("3"), passed, status, Instant.now());
    }

    private Certificate certificate(boolean revoked) {
        return new Certificate(7L, "INS-1", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2028-01-01T00:00:00Z"), BigDecimal.ONE, BigDecimal.ZERO,
                revoked, revoked ? Instant.now() : null, Instant.now());
    }

    @Test
    void eligibleMeasurementHasNoReasons() {
        List<String> reasons = ReleaseRules.evaluate(
                measurement(MeasurementStatus.PENDING, true, "alice"),
                certificate(false), "carol");
        assertTrue(reasons.isEmpty());
    }

    @Test
    void collectsAllFailureReasons() {
        List<String> reasons = ReleaseRules.evaluate(
                measurement(MeasurementStatus.RELEASED, false, "carol"),
                certificate(true), "carol");
        // 已放行优先于通用 NOT_PENDING
        assertEquals(List.of("ALREADY_RELEASED", "NOT_PASSED", "CERTIFICATE_REVOKED", "SAME_ACTOR"),
                reasons);
    }

    @Test
    void nonPendingUnknownStatusReportsNotPending() {
        List<String> reasons = ReleaseRules.evaluate(
                measurement(MeasurementStatus.PENDING, true, "alice"),
                certificate(false), "alice");
        assertEquals(List.of("SAME_ACTOR"), reasons);
    }
}
