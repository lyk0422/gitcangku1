package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;

import com.example.starter.domain.ApiException;
import com.example.starter.domain.CalibrationRecord;
import com.example.starter.domain.ReceiveTask;
import com.example.starter.domain.TaskBinding;
import com.example.starter.service.BindingService;
import com.example.starter.service.CalibrationService;
import com.example.starter.web.dto.BindRequest;
import com.example.starter.web.dto.CreateCalibrationRequest;
import com.example.starter.web.dto.CreateTaskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * 绑定规则测试：有效期边界、未通过校准、重叠占用、幂等重放与失败不占键。
 * 使用独立天线 ANT-RULE，校准 2026-03-01T08:00Z 通过，有效期至 2026-03-02T08:00Z。
 */
@SpringBootTest
class BindingRulesTest {

    @Autowired
    private CalibrationService calibrationService;

    @Autowired
    private BindingService bindingService;

    private String antenna;
    private CalibrationRecord passed;
    private CalibrationRecord failed;
    private ReceiveTask taskMorning;
    private ReceiveTask taskOverlap;
    private ReceiveTask taskTooLate;
    private ReceiveTask taskEdgeEnd;
    private ReceiveTask taskAfternoon;

    @BeforeEach
    void setUp() {
        antenna = "ANT-RULE-" + SEQ.incrementAndGet();
        passed = calibrationService.create(new CreateCalibrationRequest(antenna,
                Instant.parse("2026-03-01T08:00:00Z"), new BigDecimal("0.10"), new BigDecimal("-0.15")));
        failed = calibrationService.create(new CreateCalibrationRequest(antenna,
                Instant.parse("2026-03-01T08:30:00Z"), new BigDecimal("0.5"), new BigDecimal("0.0")));
        taskMorning = task("RULE-T1", "2026-03-01T10:00:00Z", "2026-03-01T12:00:00Z");
        taskOverlap = task("RULE-T2", "2026-03-01T11:00:00Z", "2026-03-01T13:00:00Z");
        taskTooLate = task("RULE-T3", "2026-03-02T08:00:00Z", "2026-03-02T09:00:00Z");
        taskEdgeEnd = task("RULE-T4", "2026-03-02T06:00:00Z", "2026-03-02T08:00:00Z");
        taskAfternoon = task("RULE-T5", "2026-03-01T14:00:00Z", "2026-03-01T15:00:00Z");
    }

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private ReceiveTask task(String code, String start, String end) {
        return bindingService.createTask(new CreateTaskRequest(
                code + "-" + SEQ.incrementAndGet(), antenna,
                Instant.parse(start), Instant.parse(end)));
    }

    private ApiException expectReject(BindRequest request) {
        return assertThrows(ApiException.class, () -> bindingService.bind(request));
    }

    @Test
    void failedCalibrationRejectedWith422() {
        ApiException ex = expectReject(new BindRequest("RULE-RQ-F1", taskMorning.id(), failed.id()));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("CALIBRATION_NOT_PASSED", ex.reason());
    }

    @Test
    void taskStartingAtValidityEndRejectedWith422() {
        ApiException ex = expectReject(new BindRequest("RULE-RQ-L1", taskTooLate.id(), passed.id()));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
        assertEquals("TASK_OUTSIDE_VALIDITY", ex.reason());
    }

    @Test
    void taskEndingExactlyAtValidityEndIsAccepted() {
        TaskBinding binding = bindingService.bind(
                new BindRequest("RULE-RQ-E1", taskEdgeEnd.id(), passed.id()));
        assertEquals(passed.id(), binding.calibrationId());
        assertEquals(Instant.parse("2026-03-02T08:00:00Z"), binding.calibrationValidUntil());
    }

    @Test
    void bindingFreezesSnapshotAndIdempotencyRules() {
        TaskBinding first = bindingService.bind(
                new BindRequest("RULE-RQ-M1", taskMorning.id(), passed.id()));
        assertEquals(new BigDecimal("0.1000").stripTrailingZeros(),
                first.azimuthDeviation().stripTrailingZeros());
        assertEquals(new BigDecimal("-0.15").stripTrailingZeros(),
                first.elevationDeviation().stripTrailingZeros());

        TaskBinding replay = bindingService.bind(
                new BindRequest("RULE-RQ-M1", taskMorning.id(), passed.id()));
        assertEquals(first.id(), replay.id(), "同参重放返回原绑定");

        ApiException conflict = expectReject(
                new BindRequest("RULE-RQ-M1", taskAfternoon.id(), passed.id()));
        assertEquals(HttpStatus.CONFLICT, conflict.status());
        assertEquals("REQUEST_ID_CONFLICT", conflict.reason());

        ApiException rebind = expectReject(
                new BindRequest("RULE-RQ-M2", taskMorning.id(), passed.id()));
        assertEquals("TASK_ALREADY_BOUND", rebind.reason());
    }

    @Test
    void overlapRejectedAndFailureDoesNotOccupyRequestId() {
        bindingService.bind(new BindRequest("RULE-RQ-O1", taskMorning.id(), passed.id()));

        ApiException overlap = expectReject(
                new BindRequest("RULE-RQ-O2", taskOverlap.id(), passed.id()));
        assertEquals(HttpStatus.CONFLICT, overlap.status());
        assertEquals("ANTENNA_OVERLAP", overlap.reason());
        assertTrue(bindingService.getByRequestIdOptional("RULE-RQ-O2").isEmpty(), "失败不占键");

        TaskBinding afterFix = bindingService.bind(
                new BindRequest("RULE-RQ-O2", taskAfternoon.id(), passed.id()));
        assertEquals(taskAfternoon.id(), afterFix.taskId(), "同一 requestId 修正参数后可重新绑定");
    }

    @Test
    void invalidTaskIntervalRejected() {
        ApiException ex = assertThrows(ApiException.class, () -> bindingService.createTask(
                new CreateTaskRequest("RULE-TX-" + SEQ.incrementAndGet(), antenna,
                        Instant.parse("2026-03-01T10:00:00Z"), Instant.parse("2026-03-01T10:00:00Z"))));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.status());
    }
}