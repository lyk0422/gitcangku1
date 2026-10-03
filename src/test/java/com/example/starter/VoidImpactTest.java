package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.example.starter.domain.ApiException;
import com.example.starter.domain.BindingStatus;
import com.example.starter.domain.CalibrationRecord;
import com.example.starter.domain.CalibrationStatus;
import com.example.starter.domain.ReceiveTask;
import com.example.starter.domain.TaskBinding;
import com.example.starter.service.BindingService;
import com.example.starter.service.CalibrationService;
import com.example.starter.service.CalibrationService.VoidImpact;
import com.example.starter.support.MutableClock;
import com.example.starter.support.TestClockConfig;
import com.example.starter.web.dto.BindRequest;
import com.example.starter.web.dto.CreateCalibrationRequest;
import com.example.starter.web.dto.CreateTaskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;

/**
 * 复核作废影响测试：未开始绑定转待重配、已完成任务保留旧依据并置作废标记、
 * 作废后不可再绑定、待重配不再占用天线。使用独立命名库与可控时钟。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:void_impact_test;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@Import(TestClockConfig.class)
class VoidImpactTest {

    @Autowired
    private CalibrationService calibrationService;

    @Autowired
    private BindingService bindingService;

    @Autowired
    private MutableClock clock;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private String antenna;
    private CalibrationRecord calibration;
    private ReceiveTask completedTask;
    private ReceiveTask futureTask;

    @BeforeEach
    void setUp() {
        clock.setInstant(Instant.parse("2026-05-01T08:05:00Z"));
        antenna = "ANT-VOID-" + SEQ.incrementAndGet();
        calibration = calibrationService.create(new CreateCalibrationRequest(antenna,
                Instant.parse("2026-05-01T08:00:00Z"), new BigDecimal("0.05"), new BigDecimal("0.05")));
        completedTask = bindingService.createTask(new CreateTaskRequest("VOID-T-DONE-" + SEQ.incrementAndGet(), antenna,
                Instant.parse("2026-05-01T09:00:00Z"), Instant.parse("2026-05-01T10:00:00Z")));
        futureTask = bindingService.createTask(new CreateTaskRequest("VOID-T-FUTURE-" + SEQ.incrementAndGet(), antenna,
                Instant.parse("2026-05-01T20:00:00Z"), Instant.parse("2026-05-02T07:00:00Z")));
    }

    @Test
    void voidAffectsBindingsByTaskProgress() {
        TaskBinding doneBinding = bindingService.bind(
                new BindRequest("VOID-RQ-DONE", completedTask.id(), calibration.id()));
        TaskBinding futureBinding = bindingService.bind(
                new BindRequest("VOID-RQ-FUTURE", futureTask.id(), calibration.id()));

        // 作废时刻：已完成任务已结束，未开始任务尚未开始。
        clock.setInstant(Instant.parse("2026-05-01T12:00:00Z"));
        VoidImpact impact = calibrationService.voidCalibration(calibration.id(), "复核发现偏差超差");

        assertEquals(2, impact.effects().size());
        var byTask = new java.util.HashMap<Long, String>();
        impact.effects().forEach(e -> byTask.put(e.taskId(), e.effect()));
        assertEquals("RETAINED_VOIDED_BASIS", byTask.get(completedTask.id()));
        assertEquals("PENDING_REASSIGN", byTask.get(futureTask.id()));

        TaskBinding doneAfter = bindingService.getByRequestId("VOID-RQ-DONE");
        assertEquals(BindingStatus.BOUND, doneAfter.status(), "已完成任务保留旧绑定");
        assertTrue(doneAfter.voidedMark(), "已完成任务置作废标记");
        assertEquals(calibration.id(), doneAfter.calibrationId(), "旧依据校准编号不变");
        assertEquals(new BigDecimal("0.0500").stripTrailingZeros(),
                doneAfter.azimuthDeviation().stripTrailingZeros(), "旧偏差快照不被覆盖");

        TaskBinding futureAfter = bindingService.getByRequestId("VOID-RQ-FUTURE");
        assertEquals(BindingStatus.PENDING_REASSIGN, futureAfter.status(), "未开始绑定转待重配");

        CalibrationRecord record = calibrationService.get(calibration.id());
        assertEquals(CalibrationStatus.VOIDED, record.status());
    }

    @Test
    void voidedCalibrationCannotBindAndPendingReassignFreesAntenna() {
        bindingService.bind(new BindRequest("VOID-RQ-F2", futureTask.id(), calibration.id()));
        clock.setInstant(Instant.parse("2026-05-01T12:00:00Z"));
        calibrationService.voidCalibration(calibration.id(), "复核作废");

        ApiException ex = assertThrows(ApiException.class, () -> calibrationService
                .voidCalibration(calibration.id(), "重复作废"));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertEquals("CALIBRATION_ALREADY_VOIDED", ex.reason());

        ReceiveTask newTask = bindingService.createTask(new CreateTaskRequest("VOID-T-NEW-" + SEQ.incrementAndGet(),
                antenna, Instant.parse("2026-05-01T21:00:00Z"),
                Instant.parse("2026-05-01T23:00:00Z")));
        ApiException voided = assertThrows(ApiException.class, () -> bindingService
                .bind(new BindRequest("VOID-RQ-OLD", newTask.id(), calibration.id())));
        assertEquals("CALIBRATION_VOIDED", voided.reason(), "已作废记录不能抢占天线");

        CalibrationRecord fresh = calibrationService.create(new CreateCalibrationRequest(antenna,
                Instant.parse("2026-05-01T12:30:00Z"), new BigDecimal("0.01"), new BigDecimal("0.01")));
        TaskBinding rebound = bindingService.bind(
                new BindRequest("VOID-RQ-NEW", newTask.id(), fresh.id()));
        assertEquals(fresh.id(), rebound.calibrationId(), "待重配释放天线后可用新校准绑定重叠区间");
    }

    @Test
    void calibrationChainShowsValidityAndBindings() {
        bindingService.bind(new BindRequest("VOID-RQ-C1", completedTask.id(), calibration.id()));
        clock.setInstant(Instant.parse("2026-05-01T12:00:00Z"));
        calibrationService.voidCalibration(calibration.id(), "复核作废");

        var chain = calibrationService.chain(antenna);
        assertEquals(antenna, chain.antennaCode());
        assertTrue(chain.entries().size() >= 1);
        var entry = chain.entries().stream()
                .filter(e -> e.calibration().id() == calibration.id()).findFirst().orElseThrow();
        assertEquals(Instant.parse("2026-05-02T08:00:00Z"), entry.calibration().validUntil());
        assertEquals(CalibrationStatus.VOIDED, entry.calibration().status());
        List<Long> calibrationIds = entry.bindings().stream()
                .map(b -> b.binding().calibrationId()).toList();
        assertEquals(List.of(calibration.id()), calibrationIds, "绑定固化的校准编号保持旧值");
    }
}