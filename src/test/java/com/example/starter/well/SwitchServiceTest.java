package com.example.starter.well;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.common.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 切换申请与审核的 H2 真实库测试：窗口条件、幂等、冻结快照、作废后拒绝。
 */
class SwitchServiceTest extends WellTestSupport {

    @Test
    void applySucceedsWithFourValidSamplesAndReviewFreezesSnapshot() {
        newInjectionSegment("W-SW-1");
        seedFourValidSamples("W-SW-1");

        SwitchRequest request = switchService.apply("W-SW-1", "sw-1", WellSegment.MODE_PRODUCTION);
        assertEquals(SwitchRequest.STATUS_PENDING, request.status());
        assertEquals(WellSegment.MODE_INJECTION, request.fromMode());

        Instant newStart = T0.plus(Duration.ofMinutes(1));
        SwitchRequest approved = switchService.review(request.id(), true, null, newStart);
        assertEquals(SwitchRequest.STATUS_APPROVED, approved.status());
        assertEquals(newStart, approved.newSegmentStart());
        assertEquals(T0, approved.decidedAt());

        List<SampleSnapshot> snapshot = switchService.snapshotsOf(request.id());
        assertEquals(4, snapshot.size());

        List<WellSegment> segments = wellService.timeline("W-SW-1");
        assertEquals(2, segments.size());
        assertEquals(T0, segments.get(0).endUtc());
        assertEquals(WellSegment.MODE_PRODUCTION, segments.get(1).mode());
        assertEquals(newStart, segments.get(1).startUtc());
        assertTrue(segments.get(1).startUtc().isAfter(segments.get(0).endUtc()));
    }

    @Test
    void applyRejectedWhenOneReadingIsTwelvePointOne() {
        newInjectionSegment("W-SW-2");
        sampleAt("W-SW-2", "s1", "9.60", -12);
        sampleAt("W-SW-2", "s2", "10.20", -9);
        sampleAt("W-SW-2", "s3", "12.10", -6);
        sampleAt("W-SW-2", "s4", "10.40", -3);

        ApiException ex = assertThrows(ApiException.class,
                () -> switchService.apply("W-SW-2", "sw-2", WellSegment.MODE_PRODUCTION));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertTrue(ex.getMessage().contains("12.1"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM switch_request WHERE well_no = 'W-SW-2'", Integer.class));
        assertTrue(wellService.timeline("W-SW-2").get(0).isOpen());
    }

    @Test
    void applyRejectedWhenSamplesInsufficientOrGapTooLarge() {
        newInjectionSegment("W-SW-3");
        sampleAt("W-SW-3", "s1", "10.0", -9);
        sampleAt("W-SW-3", "s2", "10.0", -6);
        sampleAt("W-SW-3", "s3", "10.0", -3);
        ApiException insufficient = assertThrows(ApiException.class,
                () -> switchService.apply("W-SW-3", "sw-3", WellSegment.MODE_PRODUCTION));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, insufficient.status());
        assertTrue(insufficient.getMessage().contains("采样不足"));

        newInjectionSegment("W-SW-4");
        sampleAt("W-SW-4", "s1", "10.0", -14);
        sampleAt("W-SW-4", "s2", "10.0", -13);
        sampleAt("W-SW-4", "s3", "10.0", -7);
        sampleAt("W-SW-4", "s4", "10.0", -1);
        ApiException gap = assertThrows(ApiException.class,
                () -> switchService.apply("W-SW-4", "sw-4", WellSegment.MODE_PRODUCTION));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, gap.status());
        assertTrue(gap.getMessage().contains("间隔"));
    }

    @Test
    void boundaryValuesPass() {
        newInjectionSegment("W-SW-5");
        sampleAt("W-SW-5", "s1", "8.00", -15);
        sampleAt("W-SW-5", "s2", "12.00", -10);
        sampleAt("W-SW-5", "s3", "8.00", -5);
        sampleAt("W-SW-5", "s4", "12.00", 0);
        SwitchRequest request = switchService.apply("W-SW-5", "sw-5", WellSegment.MODE_PRODUCTION);
        assertEquals(SwitchRequest.STATUS_PENDING, request.status());
    }

    @Test
    void applyIsIdempotentByRequestId() {
        newInjectionSegment("W-SW-6");
        seedFourValidSamples("W-SW-6");
        SwitchRequest first = switchService.apply("W-SW-6", "sw-6", WellSegment.MODE_PRODUCTION);
        SwitchRequest replay = switchService.apply("W-SW-6", "sw-6", WellSegment.MODE_PRODUCTION);
        assertEquals(first.id(), replay.id());

        ApiException conflict = assertThrows(ApiException.class,
                () -> switchService.apply("W-SW-6", "sw-6", WellSegment.MODE_INJECTION));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM switch_request WHERE well_no = 'W-SW-6'", Integer.class));
    }
}