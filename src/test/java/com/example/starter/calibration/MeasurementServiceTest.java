package com.example.starter.calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.calibration.domain.Certificate;
import com.example.starter.calibration.domain.Measurement;
import com.example.starter.calibration.domain.MeasurementStatus;
import com.example.starter.calibration.error.ApiException;
import com.example.starter.calibration.service.CertificateService;
import com.example.starter.calibration.service.InputValidation;
import com.example.starter.calibration.service.MeasurementService;
import com.example.starter.calibration.support.InMemoryRepositories;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 测量服务测试：提交、精确计算与判定边界、证书匹配、幂等键、当前可用性。
 */
class MeasurementServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant MEASURED_AT = Instant.parse("2026-01-15T12:00:00Z");

    private InMemoryRepositories repositories;
    private CertificateService certificateService;
    private MeasurementService measurementService;

    @BeforeEach
    void setUp() {
        repositories = new InMemoryRepositories();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        certificateService = new CertificateService(repositories.certificates(), clock);
        measurementService = new MeasurementService(
                repositories.measurements(),
                repositories.certificates(),
                repositories.releaseRecords(),
                clock);
    }

    private Certificate createCertificate(String a, String b) {
        return certificateService.create(
                "inst-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"),
                new BigDecimal(a),
                new BigDecimal(b));
    }

    private Measurement submit(String key, String reading, String lower, String upper) {
        return measurementService.submit(
                key,
                "inst-1",
                MEASURED_AT,
                new BigDecimal(reading),
                new BigDecimal(lower),
                new BigDecimal(upper),
                "submitter-a");
    }

    @Test
    void submitComputesExactValueAndHalfUpDisplay() {
        createCertificate("1.5", "0.25");
        // 1.5 × 3.333333 + 0.25 = 5.2499995 → 显示值 HALF_UP 4 位 = 5.2500
        Measurement measurement = submit("key-1", "3.333333", "0", "10");
        assertEquals(new BigDecimal("5.2499995"), measurement.computedValue());
        assertEquals(new BigDecimal("5.2500"), measurement.displayValue());
        assertTrue(measurement.passed());
        assertEquals(MeasurementStatus.PENDING_RELEASE, measurement.status());
    }

    @Test
    void judgmentUsesUnroundedValueNotDisplayValue() {
        createCertificate("1", "0");
        // 未舍入值 0.99995 低于下限 1.0000，虽然显示值四舍五入为 1.0000，仍判不合格
        Measurement measurement = submit("key-2", "0.99995", "1.0000", "2");
        assertEquals(new BigDecimal("1.0000"), measurement.displayValue());
        assertFalse(measurement.passed());
    }

    @Test
    void judgmentIncludesBothEndpoints() {
        createCertificate("1", "0");
        Measurement atLower = submit("key-3", "1.5", "1.5", "2.5");
        assertTrue(atLower.passed());
        Measurement atUpper = submit("key-4", "2.5", "1.5", "2.5");
        assertTrue(atUpper.passed());
        Measurement below = submit("key-5", "1.499999", "1.5", "2.5");
        assertFalse(below.passed());
        Measurement above = submit("key-6", "2.500001", "1.5", "2.5");
        assertFalse(above.passed());
    }

    @Test
    void submitWithoutMatchingCertificateReturns422() {
        // 未创建任何证书
        assertEquals(422, assertThrows(ApiException.class,
                () -> submit("key-7", "1", "0", "10")).status());
    }

    @Test
    void submitOutsideCertificateIntervalReturns422() {
        createCertificate("1", "0");
        // 测量时刻等于 validTo（右开）→ 不匹配
        assertEquals(422, assertThrows(ApiException.class,
                () -> measurementService.submit(
                        "key-8", "inst-1", Instant.parse("2026-02-01T00:00:00Z"),
                        new BigDecimal("1"), new BigDecimal("0"), new BigDecimal("10"), "submitter-a"))
                .status());
        // 测量时刻早于 validFrom → 不匹配
        assertEquals(422, assertThrows(ApiException.class,
                () -> measurementService.submit(
                        "key-9", "inst-1", Instant.parse("2025-12-31T23:59:59Z"),
                        new BigDecimal("1"), new BigDecimal("0"), new BigDecimal("10"), "submitter-a"))
                .status());
    }

    @Test
    void submitAtValidFromBoundaryMatches() {
        createCertificate("2", "1");
        Measurement measurement = measurementService.submit(
                "key-10", "inst-1", Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("3"), new BigDecimal("0"), new BigDecimal("100"), "submitter-a");
        // 2 × 3 + 1 = 7
        assertEquals(new BigDecimal("7"), measurement.computedValue());
    }

    @Test
    void revokedCertificateIsNotMatched() {
        Certificate certificate = createCertificate("1", "0");
        certificateService.revoke(certificate.id());
        assertEquals(422, assertThrows(ApiException.class,
                () -> submit("key-11", "1", "0", "10")).status());
    }

    @Test
    void duplicateMeasurementKeyReturns409() {
        createCertificate("1", "0");
        submit("key-dup", "1", "0", "10");
        assertEquals(409, assertThrows(ApiException.class,
                () -> submit("key-dup", "2", "0", "10")).status());
    }

    @Test
    void lowerLimitAboveUpperLimitReturns400() {
        createCertificate("1", "0");
        assertEquals(400, assertThrows(ApiException.class,
                () -> submit("key-12", "1", "10", "0")).status());
    }

    @Test
    void decimalParsingRejectsMoreThanSixFractionDigits() {
        assertEquals(400, assertThrows(ApiException.class,
                () -> InputValidation.parseDecimal("rawReading", "1.1234567")).status());
        assertEquals(400, assertThrows(ApiException.class,
                () -> InputValidation.parseDecimal("rawReading", "abc")).status());
        assertEquals(400, assertThrows(ApiException.class,
                () -> InputValidation.parseDecimal("rawReading", "1.2.3")).status());
        assertEquals(new BigDecimal("-12.123456"), InputValidation.parseDecimal("f", "-12.123456"));
        assertEquals(new BigDecimal("7"), InputValidation.parseDecimal("f", "7"));
    }

    @Test
    void instantParsingRequiresOffset() {
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"),
                InputValidation.parseInstant("validFrom", "2026-01-01T08:00:00+08:00"));
        assertEquals(400, assertThrows(ApiException.class,
                () -> InputValidation.parseInstant("validFrom", "2026-01-01 00:00:00")).status());
        assertEquals(400, assertThrows(ApiException.class,
                () -> InputValidation.parseInstant("validFrom", "not-a-time")).status());
    }

    @Test
    void historyIsRetainedAndCurrentUsableDropsAfterRevoke() {
        Certificate certificate = createCertificate("1", "0");
        Measurement measurement = submit("key-13", "1", "0", "10");
        // 手动放行（直接置位），模拟已放行结果
        repositories.measurements().markReleased(measurement.id(), "releaser-b", NOW);

        // 撤销前：当前可用包含该结果
        assertEquals(1, measurementService.findCurrentUsable("inst-1").size());
        certificateService.revoke(certificate.id());
        // 撤销后：当前可用为空，但历史明细仍保留
        assertTrue(measurementService.findCurrentUsable("inst-1").isEmpty());
        MeasurementService.MeasurementHistory history = measurementService.getHistory(measurement.id());
        assertEquals(MeasurementStatus.RELEASED, history.measurement().status());
        assertEquals("releaser-b", history.measurement().releasedBy());
        assertTrue(history.certificateRevoked());
        assertEquals(new BigDecimal("1"), history.measurement().computedValue());
    }

    @Test
    void getHistoryOfMissingMeasurementReturns404() {
        assertEquals(404, assertThrows(ApiException.class,
                () -> measurementService.getHistory(424242L)).status());
    }

    @Test
    void historyOfPendingMeasurementHasNoRelease() {
        createCertificate("1", "0");
        Measurement measurement = submit("key-14", "1", "0", "10");
        MeasurementService.MeasurementHistory history = measurementService.getHistory(measurement.id());
        assertNull(history.release());
        assertNotNull(history.measurement());
    }
}
