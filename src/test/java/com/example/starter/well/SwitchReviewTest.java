package com.example.starter.well;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.common.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 审核决定的 H2 真实库测试：拒绝原因、作废导致条件失效、快照不变、重复审核冲突。
 */
class SwitchReviewTest extends WellTestSupport {

    private SwitchRequest newPendingRequest(String wellNo, String requestId) {
        newInjectionSegment(wellNo);
        seedFourValidSamples(wellNo);
        return switchService.apply(wellNo, requestId, WellSegment.MODE_PRODUCTION);
    }

    @Test
    void rejectKeepsOldModeEffectiveAndRecordsReason() {
        SwitchRequest request = newPendingRequest("W-RV-1", "rv-1");
        SwitchRequest rejected = switchService.review(request.id(), false, "调度未确认", null);
        assertEquals(SwitchRequest.STATUS_REJECTED, rejected.status());
        assertEquals("调度未确认", rejected.rejectReason());

        List<WellSegment> segments = wellService.timeline("W-RV-1");
        assertEquals(1, segments.size());
        assertTrue(segments.get(0).isOpen());
        assertEquals(WellSegment.MODE_INJECTION, segments.get(0).mode());
    }

    @Test
    void approveRejectsWhenVoidedReadingBreaksCondition() {
        SwitchRequest request = newPendingRequest("W-RV-2", "rv-2");
        Long sampleId = jdbc.queryForObject(
                "SELECT id FROM pressure_sample WHERE well_no = 'W-RV-2' AND request_id = 'W-RV-2-s2'",
                Long.class);
        // 替代读数放在 40 分钟前，落在 15 分钟窗口之外，作废后窗口内只剩 3 个有效采样。
        wellService.voidSample(sampleId, "读数跳变", "rv-2-fix", new BigDecimal("10.0"),
                T0.minus(Duration.ofMinutes(40)));

        SwitchRequest decided = switchService.review(request.id(), true, null,
                T0.plus(Duration.ofMinutes(1)));
        assertEquals(SwitchRequest.STATUS_REJECTED, decided.status());
        assertNotNull(decided.rejectReason());
        assertTrue(decided.rejectReason().contains("采样不足"));
        assertTrue(wellService.timeline("W-RV-2").get(0).isOpen());
    }

    @Test
    void approvedSnapshotStaysFrozenWhenSourceSampleIsVoidedLater() {
        SwitchRequest request = newPendingRequest("W-RV-3", "rv-3");
        SwitchRequest approved = switchService.review(request.id(), true, null,
                T0.plus(Duration.ofMinutes(1)));
        assertEquals(SwitchRequest.STATUS_APPROVED, approved.status());
        List<SampleSnapshot> before = switchService.snapshotsOf(request.id());
        assertEquals(4, before.size());

        // 推进时钟，使替代读数时刻不晚于当前时刻。
        clock.advance(Duration.ofMinutes(5));
        wellService.voidSample(before.get(0).sampleId(), "事后发现异常", "rv-3-fix",
                new BigDecimal("9.90"), T0.plus(Duration.ofMinutes(2)));

        List<SampleSnapshot> after = switchService.snapshotsOf(request.id());
        assertEquals(before, after);
        Boolean voided = jdbc.queryForObject("SELECT voided FROM pressure_sample WHERE id = ?",
                Boolean.class, before.get(0).sampleId());
        assertTrue(voided);
    }

    @Test
    void reReviewOfDecidedRequestConflicts() {
        SwitchRequest request = newPendingRequest("W-RV-4", "rv-4");
        switchService.review(request.id(), true, null, T0.plus(Duration.ofMinutes(1)));
        ApiException conflict = assertThrows(ApiException.class,
                () -> switchService.review(request.id(), false, "再次审核", null));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals(2, wellService.timeline("W-RV-4").size());
    }

    @Test
    void approveRequiresNewStartAfterOldSegmentEnd() {
        SwitchRequest request = newPendingRequest("W-RV-5", "rv-5");
        ApiException missing = assertThrows(ApiException.class,
                () -> switchService.review(request.id(), true, null, null));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, missing.status());

        ApiException notAfter = assertThrows(ApiException.class,
                () -> switchService.review(request.id(), true, null, T0));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, notAfter.status());

        SwitchRequest still = switchService.review(request.id(), true, null,
                T0.plus(Duration.ofSeconds(1)));
        assertEquals(SwitchRequest.STATUS_APPROVED, still.status());
    }

    @Test
    void reviewOfUnknownRequestReturnsNotFound() {
        ApiException ex = assertThrows(ApiException.class,
                () -> switchService.review(999999L, true, null, T0.plusSeconds(1)));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
    }
}