package com.example.starter.pass.service;

import com.example.starter.pass.api.AllocateRequest;
import com.example.starter.pass.api.CancelRequest;
import com.example.starter.pass.api.CompleteRequest;
import com.example.starter.pass.api.PredictionUpsertRequest;
import com.example.starter.pass.api.ReassignRequest;
import com.example.starter.pass.api.SlotView;
import com.example.starter.pass.domain.AllocationStatus;
import com.example.starter.pass.domain.BusinessRuleException;
import com.example.starter.pass.domain.RejectReason;
import com.example.starter.pass.support.FixedTimeSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 H2（MODE=MySQL）上的业务集成测试：主流程、可区分拒绝、幂等重放与预测修订联动，
 * 全部通过 JDBC 核对最终落库状态，而不是只看返回值。
 */
@SpringBootTest
@ActiveProfiles("test")
class PassFlowIntegrationTest {

    private static final Instant T1200 = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant T1202 = Instant.parse("2026-10-01T12:02:00Z");
    private static final Instant T1204 = Instant.parse("2026-10-01T12:04:00Z");
    private static final Instant T1205 = Instant.parse("2026-10-01T12:05:00Z");
    private static final Instant T1206 = Instant.parse("2026-10-01T12:06:00Z");
    private static final Instant T1207 = Instant.parse("2026-10-01T12:07:00Z");
    private static final Instant T1208 = Instant.parse("2026-10-01T12:08:00Z");
    private static final Instant T1209 = Instant.parse("2026-10-01T12:09:00Z");
    private static final Instant T1210 = Instant.parse("2026-10-01T12:10:00Z");
    private static final Instant T1212 = Instant.parse("2026-10-01T12:12:00Z");
    private static final Instant T1215 = Instant.parse("2026-10-01T12:15:00Z");

    @Autowired
    private PassService passService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FixedTimeSource clock;

    /** 本测试类创建的卫星，@AfterEach 清理对应场景数据。 */
    private final List<String> createdSatellites = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (createdSatellites.isEmpty()) {
            return;
        }
        String in = String.join(",", createdSatellites.stream().map(s -> "?").toList());
        jdbcTemplate.update("DELETE FROM idempotent_request WHERE allocation_id IN "
                + "(SELECT id FROM slot_allocation WHERE satellite_code IN (" + in + "))",
                createdSatellites.toArray());
        jdbcTemplate.update("DELETE FROM slot_allocation WHERE satellite_code IN (" + in + ")",
                createdSatellites.toArray());
        jdbcTemplate.update("DELETE FROM pass_prediction WHERE satellite_code IN (" + in + ")",
                createdSatellites.toArray());
        jdbcTemplate.update("DELETE FROM satellite WHERE code IN (" + in + ")",
                createdSatellites.toArray());
        createdSatellites.clear();
    }

    private String newSatellite() {
        String code = "S-" + UUID.randomUUID().toString().substring(0, 8);
        createdSatellites.add(code);
        return code;
    }

    private void predict(String sat, Instant start, Instant end, String elev, long bytes) {
        passService.upsertPrediction(new PredictionUpsertRequest(
                sat, start, end, new BigDecimal(elev), bytes));
    }

    private AllocateRequest allocateRequest(String sat, String station, Instant start, Instant end) {
        return new AllocateRequest("REQ-" + UUID.randomUUID(), sat, station, start, end);
    }

    @Test
    void fullFlowConfirmsWithSnapshotsThenRecordsActualBytes() {
        clock.setInstant(Instant.parse("2026-10-01T09:00:00Z"));
        String sat = newSatellite();
        predict(sat, T1200, T1210, "15.00", 300_000L);

        AllocateRequest req = allocateRequest(sat, "A", T1200, T1205);
        SlotView slot = passService.allocate(req);
        long slotId = slot.id();

        assertEquals(AllocationStatus.CONFIRMED.name(), slot.status());
        assertEquals(1, slot.predictionRevisionNo());
        assertEquals("A", slot.stationCode());
        assertEquals("ANT-A", slot.antennaCode());
        assertEquals(new BigDecimal("15.00"), slot.passMinElevationDeg());
        assertEquals(new BigDecimal("10.00"), slot.stationMinElevationDeg());
        assertEquals(60_000L, slot.stationBytesPerMinute());
        assertEquals(300_000L, slot.plannedBytes());
        assertEquals(Instant.parse("2026-10-01T09:00:00Z"), slot.confirmedAt());
        assertNull(slot.actualBytes());
        assertNull(slot.cancelReason());

        // 查询面：预测版本时间线、时隙时间线、按状态查询、按 requestId 查询
        assertEquals(1, passService.getPredictionVersions(sat).size());
        assertEquals(1, passService.getTimeline(sat).size());
        assertEquals(slotId, passService.findSlotByRequestId(req.requestId()).id());
        assertTrue(passService.getSlotsByStatus("CONFIRMED").stream().anyMatch(s -> s.id() == slotId));

        clock.setInstant(Instant.parse("2026-10-01T12:05:30Z"));
        String completeReqId = "CMP-" + UUID.randomUUID();
        SlotView received = passService.complete(slotId, new CompleteRequest(completeReqId, 250_000L));
        assertEquals(AllocationStatus.RECEIVED.name(), received.status());
        assertEquals(250_000L, received.actualBytes());
        assertEquals(Instant.parse("2026-10-01T12:05:30Z"), received.receivedAt());
        assertEquals(1, received.predictionRevisionNo());

        // 同参重放返回首次结果
        SlotView replay = passService.complete(slotId, new CompleteRequest(completeReqId, 250_000L));
        assertEquals(received.id(), replay.id());
        assertEquals(250_000L, replay.actualBytes());

        // 异参重放 409
        BusinessRuleException mismatch = assertThrows(BusinessRuleException.class,
                () -> passService.complete(slotId, new CompleteRequest(completeReqId, 250_001L)));
        assertEquals(RejectReason.REQUEST_PARAM_MISMATCH, mismatch.reason());

        // 已接收不能再取消，终态唯一
        BusinessRuleException terminal = assertThrows(BusinessRuleException.class,
                () -> passService.cancel(slotId, new CancelRequest("late cancel")));
        assertEquals(RejectReason.SLOT_ALREADY_TERMINATED, terminal.reason());

        // JDBC 终态核对
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM slot_allocation WHERE id = ? AND status = 'RECEIVED' "
                        + "AND actual_bytes = 250000 AND cancel_reason IS NULL AND antenna_code = 'ANT-A'",
                Integer.class, slotId);
        assertEquals(1, count);
    }

    @Test
    void rejectsAreDistinguishableAndLeaveNoPartialOccupation() {
        String sat = newSatellite();
        predict(sat, T1200, T1210, "15.00", 600_000L);

        // 窗口缺口：申请窗口超出可见窗口
        BusinessRuleException gap = assertThrows(BusinessRuleException.class,
                () -> passService.allocate(allocateRequest(sat, "A", T1205, T1215)));
        assertEquals(RejectReason.WINDOW_GAP, gap.reason());

        // 仰角不足：站点 B 门槛 20 度，预测最低仰角 15
        BusinessRuleException elevation = assertThrows(BusinessRuleException.class,
                () -> passService.allocate(allocateRequest(sat, "B", T1200, T1205)));
        assertEquals(RejectReason.ELEVATION_TOO_LOW, elevation.reason());

        // 超容量：5 分钟容量 300000，计划量 600000
        BusinessRuleException capacity = assertThrows(BusinessRuleException.class,
                () -> passService.allocate(allocateRequest(sat, "A", T1200, T1205)));
        assertEquals(RejectReason.CAPACITY_EXCEEDED, capacity.reason());

        // 拒绝不留下时隙或幂等记录
        assertEquals(0, passService.getTimeline(sat).size());
        Integer idempotentRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM idempotent_request ir WHERE NOT EXISTS "
                        + "(SELECT 1 FROM slot_allocation sa WHERE sa.id = ir.allocation_id)",
                Integer.class);
        assertEquals(0, idempotentRows);

        // 主流程：12:00-12:10 整段 600000 恰好放下
        AllocateRequest first = allocateRequest(sat, "A", T1200, T1210);
        SlotView held = passService.allocate(first);
        assertEquals(AllocationStatus.CONFIRMED.name(), held.status());

        // 同天线重叠（窗口本身满足容量）→ 409
        BusinessRuleException conflict = assertThrows(BusinessRuleException.class,
                () -> passService.allocate(allocateRequest(sat, "A", T1200, T1210)));
        assertEquals(RejectReason.ANTENNA_CONFLICT, conflict.reason());

        // 相邻窗口（半开区间相接）不视为重叠：高仰角卫星在站点 B（ANT-B，门槛 20 度），
        // 30000 字节/分钟 × 5 分钟恰好容纳 150000；12:00-12:05 与 12:05-12:10 可分别确认。
        String adjSat = newSatellite();
        predict(adjSat, T1200, T1210, "25.00", 150_000L);
        SlotView early = passService.allocate(allocateRequest(adjSat, "B", T1200, T1205));
        SlotView late = passService.allocate(allocateRequest(adjSat, "B", T1205, T1210));
        assertEquals(AllocationStatus.CONFIRMED.name(), early.status());
        assertEquals(AllocationStatus.CONFIRMED.name(), late.status());
        // 同样 5 分钟容量充足但跨越相接点的窗口 → 重叠 409
        BusinessRuleException crossConflict = assertThrows(BusinessRuleException.class,
                () -> passService.allocate(allocateRequest(adjSat, "B", T1202, T1207)));
        assertEquals(RejectReason.ANTENNA_CONFLICT, crossConflict.reason());

        // 同 requestId 同参重放返回同一时隙
        assertEquals(held.id(), passService.allocate(first).id());
        // 同 requestId 异参 409
        AllocateRequest mutated = new AllocateRequest(first.requestId(), sat, "A", T1200, T1205);
        BusinessRuleException mismatch = assertThrows(BusinessRuleException.class,
                () -> passService.allocate(mutated));
        assertEquals(RejectReason.REQUEST_PARAM_MISMATCH, mismatch.reason());

        // 取消后天线释放，同窗口申请可确认
        passService.cancel(held.id(), new CancelRequest("任务优先级调整，取消该接收计划"));
        SlotView rebook = passService.allocate(allocateRequest(sat, "A", T1200, T1210));
        assertEquals(AllocationStatus.CONFIRMED.name(), rebook.status());

        SlotView cancelled = passService.getSlot(held.id());
        assertEquals(AllocationStatus.CANCELLED.name(), cancelled.status());
        assertNotNull(cancelled.cancelReason());
        assertEquals("任务优先级调整，取消该接收计划", cancelled.cancelReason());
    }

    @Test
    void revisionRechecksOpenSlotsAndPreservesReceivedEvidence() {
        String sat = newSatellite();
        predict(sat, T1200, T1210, "15.00", 300_000L);

        SlotView open = passService.allocate(allocateRequest(sat, "A", T1200, T1205));
        SlotView finished = passService.allocate(allocateRequest(sat, "A", T1205, T1210));
        passService.complete(finished.id(),
                new CompleteRequest("CMP-" + UUID.randomUUID(), 200_000L));

        // 第二次提交预测（同窗口）：未开始时隙快照推进到 rev2；已接收时隙保留 rev1 证据
        predict(sat, T1200, T1210, "15.00", 300_000L);
        assertEquals(2, passService.getSlot(open.id()).predictionRevisionNo());
        SlotView finishedKept = passService.getSlot(finished.id());
        assertEquals(AllocationStatus.RECEIVED.name(), finishedKept.status());
        assertEquals(1, finishedKept.predictionRevisionNo());
        assertEquals(200_000L, finishedKept.actualBytes());

        // 第三次修订把窗口平移到 12:02-12:12：open 原窗口不再被完整容纳 → 待重新分配并释放天线
        predict(sat, T1202, T1212, "15.00", 300_000L);
        SlotView pending = passService.getSlot(open.id());
        assertEquals(AllocationStatus.PENDING_REASSIGN.name(), pending.status());
        assertEquals(RejectReason.WINDOW_GAP.name(), pending.pendingReason());
        assertNull(pending.antennaCode());
        assertNull(pending.confirmedAt());
        assertTrue(passService.getSlotsByStatus("PENDING_REASSIGN").stream()
                .anyMatch(s -> s.id() == open.id()));

        // 天线已释放：别的计划可占用与原窗口重叠的 12:02-12:07（5 分钟恰好容纳 300000）
        SlotView newHolder = passService.allocate(allocateRequest(sat, "A", T1202, T1207));
        assertEquals(AllocationStatus.CONFIRMED.name(), newHolder.status());
        assertEquals(3, newHolder.predictionRevisionNo());

        // 重分配到 B：仰角门槛 20 不满足（12:07-12:12 容量本身达标）
        BusinessRuleException elevation = assertThrows(BusinessRuleException.class,
                () -> passService.reassign(open.id(), new ReassignRequest("B", T1207, T1212)));
        assertEquals(RejectReason.ELEVATION_TOO_LOW, elevation.reason());

        // 重分配窗口与 newHolder 完全重叠（容量达标但天线冲突）→ 409
        BusinessRuleException conflict = assertThrows(BusinessRuleException.class,
                () -> passService.reassign(open.id(), new ReassignRequest("A", T1202, T1207)));
        assertEquals(RejectReason.ANTENNA_CONFLICT, conflict.reason());

        // 合法重分配：相接窗口 12:07-12:12，固化 rev3 与容量快照
        SlotView reassigned = passService.reassign(open.id(), new ReassignRequest("A", T1207, T1212));
        assertEquals(AllocationStatus.CONFIRMED.name(), reassigned.status());
        assertEquals(3, reassigned.predictionRevisionNo());
        assertEquals("ANT-A", reassigned.antennaCode());

        // 已确认时隙不能再次重分配（用合法窗口，先命中状态检查）
        BusinessRuleException notPending = assertThrows(BusinessRuleException.class,
                () -> passService.reassign(open.id(), new ReassignRequest("A", T1208, T1209)));
        assertEquals(RejectReason.SLOT_NOT_PENDING, notPending.reason());

        // 版本时间线：rev1、rev2 已失效，rev3 有效
        var versions = passService.getPredictionVersions(sat);
        assertEquals(3, versions.size());
        assertEquals(3, versions.get(0).revisionNo());
        assertFalse(versions.get(0).superseded());
        assertTrue(versions.get(1).superseded());
        assertTrue(versions.get(2).superseded());
    }

    @Test
    void invalidCompletionAndCancelGuardsDoNotMutateState() {
        String sat = newSatellite();
        predict(sat, T1200, T1210, "15.00", 300_000L);
        SlotView slot = passService.allocate(allocateRequest(sat, "A", T1200, T1205));

        // 实际字节超过计划量 → 拒绝，时隙保持 CONFIRMED 且无完成标记
        BusinessRuleException over = assertThrows(BusinessRuleException.class,
                () -> passService.complete(slot.id(),
                        new CompleteRequest("CMP-" + UUID.randomUUID(), 300_001L)));
        assertEquals(RejectReason.ACTUAL_BYTES_INVALID, over.reason());
        SlotView stillConfirmed = passService.getSlot(slot.id());
        assertEquals(AllocationStatus.CONFIRMED.name(), stillConfirmed.status());
        assertNull(stillConfirmed.actualBytes());
        assertNull(stillConfirmed.receivedAt());

        // 资源不存在分支
        assertEquals(RejectReason.NOT_FOUND, assertThrows(BusinessRuleException.class,
                () -> passService.allocate(allocateRequest(newSatellite(), "A", T1200, T1205))).reason());
        assertEquals(RejectReason.NOT_FOUND, assertThrows(BusinessRuleException.class,
                () -> passService.allocate(allocateRequest(sat, "Z", T1200, T1205))).reason());
        assertEquals(RejectReason.NOT_FOUND, assertThrows(BusinessRuleException.class,
                () -> passService.complete(999_999L,
                        new CompleteRequest("CMP-" + UUID.randomUUID(), 1L))).reason());
        assertEquals(RejectReason.INVALID_REQUEST, assertThrows(BusinessRuleException.class,
                () -> passService.getSlotsByStatus("NOPE")).reason());

        // 取消后不能重复取消，也不能再回报完成
        passService.cancel(slot.id(), new CancelRequest("天气原因取消"));
        assertEquals(RejectReason.SLOT_ALREADY_TERMINATED, assertThrows(BusinessRuleException.class,
                () -> passService.cancel(slot.id(), new CancelRequest("再取消一次"))).reason());
        assertEquals(RejectReason.SLOT_ALREADY_TERMINATED, assertThrows(BusinessRuleException.class,
                () -> passService.complete(slot.id(),
                        new CompleteRequest("CMP-" + UUID.randomUUID(), 1L))).reason());

        // JDBC 核对：取消终态不残留接收标记（天线编码与确认时间作为历史快照保留，释放由状态离开 CONFIRMED 体现）
        Integer cancelledClean = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM slot_allocation WHERE id = ? AND status = 'CANCELLED' "
                        + "AND actual_bytes IS NULL AND received_at IS NULL "
                        + "AND cancel_reason = '天气原因取消' AND cancelled_at IS NOT NULL",
                Integer.class, slot.id());
        assertEquals(1, cancelledClean);
    }
}
