package com.example.starter.well;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.common.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 运行段与采样的 H2 真实库测试：建段冲突、采样幂等、时间逆序、作废与替代。
 */
class WellServiceTest extends WellTestSupport {

    @Test
    void createSegmentSucceedsAndRejectsOverlappingOpenSegment() {
        WellSegment segment = newInjectionSegment("W-SEG-1");
        assertTrue(segment.isOpen());
        assertEquals(WellSegment.MODE_INJECTION, segment.mode());

        ApiException conflict = assertThrows(ApiException.class,
                () -> wellService.createSegment("W-SEG-1", WellSegment.MODE_PRODUCTION,
                        T0.plus(Duration.ofMinutes(1))));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM well_segment WHERE well_no = 'W-SEG-1'", Integer.class));
    }

    @Test
    void addSampleIsIdempotentByRequestId() {
        newInjectionSegment("W-SMP-1");
        PressureSample first = sampleAt("W-SMP-1", "req-1", "10.00", -5);
        PressureSample replay = sampleAt("W-SMP-1", "req-1", "10.00", -5);
        assertEquals(first.id(), replay.id());

        ApiException conflict = assertThrows(ApiException.class,
                () -> sampleAt("W-SMP-1", "req-1", "11.50", -5));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM pressure_sample WHERE well_no = 'W-SMP-1'", Integer.class));
    }

    @Test
    void addSampleRejectsTimeInversion() {
        WellSegment segment = newInjectionSegment("W-SMP-2");
        ApiException beforeStart = assertThrows(ApiException.class,
                () -> wellService.addSample("W-SMP-2", "req-early", new BigDecimal("10.0"),
                        segment.startUtc().minusSeconds(1)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, beforeStart.status());

        ApiException future = assertThrows(ApiException.class,
                () -> wellService.addSample("W-SMP-2", "req-future", new BigDecimal("10.0"),
                        T0.plusSeconds(1)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, future.status());
    }

    @Test
    void addSampleRequiresOpenSegment() {
        ApiException ex = assertThrows(ApiException.class,
                () -> sampleAt("W-SMP-3", "req-1", "10.0", -1));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
    }

    @Test
    void voidSampleAppendsMarkerAndReplacementWithoutModifyingOriginal() {
        newInjectionSegment("W-VOID-1");
        PressureSample bad = sampleAt("W-VOID-1", "req-bad", "15.00", -5);

        PressureSample voided = wellService.voidSample(bad.id(), "仪表漂移", "req-fix",
                new BigDecimal("10.50"), T0.minus(Duration.ofMinutes(4)));
        assertTrue(voided.voided());
        assertEquals("仪表漂移", voided.voidReason());
        assertNotNull(voided.replacedById());

        PressureSample replacement = jdbc.queryForObject(
                "SELECT pressure_mpa, voided FROM pressure_sample WHERE id = ?",
                (rs, rowNum) -> new PressureSample(0, "", "", "", rs.getBigDecimal(1),
                        null, rs.getBoolean(2), null, null), voided.replacedById());
        assertEquals(0, new BigDecimal("10.50").compareTo(replacement.pressureMpa()));
        assertFalse(replacement.voided());

        BigDecimal original = jdbc.queryForObject(
                "SELECT pressure_mpa FROM pressure_sample WHERE id = ?", BigDecimal.class, bad.id());
        assertEquals(0, new BigDecimal("15.00").compareTo(original));

        ApiException secondVoid = assertThrows(ApiException.class,
                () -> wellService.voidSample(bad.id(), "重复作废", "req-fix-2",
                        new BigDecimal("10.0"), T0.minus(Duration.ofMinutes(4))));
        assertEquals(HttpStatus.CONFLICT, secondVoid.status());
    }
}