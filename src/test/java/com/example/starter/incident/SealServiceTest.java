package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.SealAppendixRequest;
import com.example.starter.incident.dto.Requests.SealCreateRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.SealAppendixView;
import com.example.starter.incident.dto.Responses.SealDiagnosticsView;
import com.example.starter.incident.dto.Responses.SealEntryView;
import com.example.starter.incident.dto.Responses.SealHistoryView;
import com.example.starter.incident.dto.Responses.SealPackageDetailView;
import com.example.starter.incident.dto.Responses.SealPackageView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 指挥日志封存服务测试：覆盖封存主流程（递增版本、左闭右开窗口、排序、记录版本固化）、
 * 截至时刻边界、同刻指令冲突、依赖图违规、不可变性、更正附录与幂等语义。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class SealServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private SealService sealService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_seal_appendices");
        jdbc.update("DELETE FROM incident_seal_edges");
        jdbc.update("DELETE FROM incident_seal_entries");
        jdbc.update("DELETE FROM incident_seal_packages");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "封存场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void addAction(String incidentKey, String actor, String actionKey, Instant occurredAt) {
        incidentService.addAction(incidentKey, actor,
                new ActionRequest(key(), actionKey, "NOTE", "note-" + actionKey, occurredAt));
    }

    private SealPackageView seal(String incidentKey, String actor, Instant cutoff) {
        return sealService.seal(incidentKey, actor, new SealCreateRequest(key(), cutoff));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void sealMainFlow_incrementalVersionsAndImmutableSnapshot() {
        commanding("INC-S1", "alice");
        addAction("INC-S1", "alice", "A2", T0.plusSeconds(10));
        addAction("INC-S1", "alice", "A1", T0.plusSeconds(10));
        incidentService.createTask("INC-S1", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "任务一", List.of()));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(30));
        incidentService.initiateTransfer("INC-S1", "alice", new TransferRequest(key(), "bob"));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(40));
        incidentService.acceptTransfer("INC-S1", "bob", new TransferAcceptRequest(key()));

        // 第一包：截至 T0+60，窗口 [T0, T0+60)
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        SealPackageView v1 = seal("INC-S1", "bob", T0.plusSeconds(60));
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.lowerBoundAt()).isEqualTo(T0);
        assertThat(v1.cutoffAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(v1.sealedBy()).isEqualTo("bob");

        SealPackageDetailView d1 = sealService.sealDetail("INC-S1", 1);
        // 条目：创建/接管 2 条状态流转 + 2 条指令 + 1 条交接 + 1 条任务快照
        assertThat(d1.entries()).hasSize(6);
        assertThat(d1.entryCount()).isEqualTo(6);
        // 排序：按发生时刻，同刻按记录标识（A1 先于 A2）
        List<String> orderedKeys = d1.entries().stream().map(SealEntryView::recordKey).toList();
        assertThat(orderedKeys).containsSubsequence("A1", "A2");
        assertThat(d1.entries().stream().map(SealEntryView::seqNo))
                .containsExactly(1, 2, 3, 4, 5, 6);
        SealEntryView taskV1 = d1.entries().stream()
                .filter(e -> e.recordType().equals("TASK")).findFirst().orElseThrow();
        assertThat(taskV1.recordVersion()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> taskPayloadV1 = (Map<String, Object>) taskV1.payload();
        assertThat(taskPayloadV1.get("status")).isEqualTo("OPEN");

        // 封存后任务被取消、追加新指令：不得改写 v1 内容
        ((ControllableClock) clock).setInstant(T0.plusSeconds(110));
        incidentService.cancelTask("INC-S1", "T-1", "bob", new TaskActionRequest(key()));
        addAction("INC-S1", "bob", "A3", T0.plusSeconds(70));

        ((ControllableClock) clock).setInstant(T0.plusSeconds(200));
        SealPackageView v2 = seal("INC-S1", "bob", T0.plusSeconds(150));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.lowerBoundAt()).isEqualTo(T0.plusSeconds(60));

        SealPackageDetailView d2 = sealService.sealDetail("INC-S1", 2);
        // 第二包：A3 + 任务新快照（取消后状态），共 2 条
        assertThat(d2.entries()).hasSize(2);
        SealEntryView taskV2 = d2.entries().stream()
                .filter(e -> e.recordType().equals("TASK")).findFirst().orElseThrow();
        assertThat(taskV2.recordVersion()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        Map<String, Object> taskPayloadV2 = (Map<String, Object>) taskV2.payload();
        assertThat(taskPayloadV2.get("status")).isEqualTo("CANCELLED");

        // v1 快照保持 OPEN，不被后续撤销改写
        SealPackageDetailView d1Again = sealService.sealDetail("INC-S1", 1);
        SealEntryView taskV1Again = d1Again.entries().stream()
                .filter(e -> e.recordType().equals("TASK")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> taskPayloadV1Again = (Map<String, Object>) taskV1Again.payload();
        assertThat(taskPayloadV1Again.get("status")).isEqualTo("OPEN");

        // 历史：两个版本递增的包
        SealHistoryView history = sealService.sealHistory("INC-S1");
        assertThat(history.packages().stream().map(SealPackageView::version))
                .containsExactly(1, 2);
    }

    @Test
    void sealWindow_leftClosedRightOpen() {
        commanding("INC-S2", "alice");
        addAction("INC-S2", "alice", "A-IN", T0.plusSeconds(59));
        addAction("INC-S2", "alice", "A-OUT", T0.plusSeconds(61));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        SealPackageView v1 = seal("INC-S2", "alice", T0.plusSeconds(60));

        SealPackageDetailView d1 = sealService.sealDetail("INC-S2", 1);
        List<String> actionKeys = d1.entries().stream()
                .filter(e -> e.recordType().equals("ACTION"))
                .map(SealEntryView::recordKey).toList();
        // 右开：cutoff 之后的不入包
        assertThat(actionKeys).containsExactly("A-IN");

        // 左闭：封存后补写 occurredAt 恰好等于上一包截至时刻的指令，落入下一包
        addAction("INC-S2", "alice", "A-EDGE", T0.plusSeconds(60));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(200));
        seal("INC-S2", "alice", T0.plusSeconds(120));
        SealPackageDetailView d2 = sealService.sealDetail("INC-S2", 2);
        List<String> actionKeys2 = d2.entries().stream()
                .filter(e -> e.recordType().equals("ACTION"))
                .map(SealEntryView::recordKey).toList();
        assertThat(actionKeys2).containsExactly("A-EDGE", "A-OUT");
    }

    @Test
    void sealCutoff_outOfBounds_400() {
        commanding("INC-S3", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        // 早于事件创建
        assertThatThrownBy(() -> seal("INC-S3", "alice", T0.minusSeconds(1)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("actual=").contains("required>=");
                });
        // 晚于当前时刻
        assertThatThrownBy(() -> seal("INC-S3", "alice", T0.plusSeconds(101)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains("actual=").contains("required<=");
                });
        // cutoffAt 为空
        assertApiStatus(() -> sealService.seal("INC-S3", "alice",
                new SealCreateRequest(key(), null)), HttpStatus.BAD_REQUEST);
    }

    @Test
    void sealCutoff_notAfterLastCutoff_409() {
        commanding("INC-S4", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        seal("INC-S4", "alice", T0.plusSeconds(60));
        assertApiStatus(() -> seal("INC-S4", "alice", T0.plusSeconds(60)),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> seal("INC-S4", "alice", T0.plusSeconds(30)),
                HttpStatus.CONFLICT);
    }

    @Test
    void sealSameInstantAction_409_thenOtherCutoffSucceeds() {
        commanding("INC-S5", "alice");
        addAction("INC-S5", "alice", "A-SAME", T0.plusSeconds(60));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        // 同刻指令写入冲突：409 且明细携带指令标识
        assertThatThrownBy(() -> seal("INC-S5", "alice", T0.plusSeconds(60)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat((List<Object>) e.details()).containsExactly("A-SAME");
                });
        // 选择其他截至时刻后成功，且该指令落入包内
        seal("INC-S5", "alice", T0.plusSeconds(61));
        SealPackageDetailView detail = sealService.sealDetail("INC-S5", 1);
        assertThat(detail.entries().stream().map(SealEntryView::recordKey))
                .contains("A-SAME");
    }

    @Test
    void sealClosedIncident_422_butAppendixAndQueryAllowed() {
        commanding("INC-S6", "alice");
        addAction("INC-S6", "alice", "A1", T0.plusSeconds(10));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        seal("INC-S6", "alice", T0.plusSeconds(60));
        incidentService.changeStatus("INC-S6", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-S6", "alice", new StatusRequest(key(), "RESOLVED"));
        incidentService.changeStatus("INC-S6", "alice", new StatusRequest(key(), "CLOSED"));

        // 已关闭事件不得新增封存包
        assertApiStatus(() -> seal("INC-S6", "alice", T0.plusSeconds(90)),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 已关闭事件允许查询
        assertThat(sealService.sealHistory("INC-S6").packages()).hasSize(1);
        // 已关闭事件允许追加更正附录
        SealAppendixView appendix = sealService.appendAppendix("INC-S6", "auditor-1",
                new SealAppendixRequest(key(), 1, "A1", "{\"note\":\"更正后\"}", "事实错误"));
        assertThat(appendix.actor()).isEqualTo("auditor-1");
        assertThat(appendix.packageVersion()).isEqualTo(1);
    }

    @Test
    void sealRequiresCommander_409() {
        commanding("INC-S7", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        assertApiStatus(() -> seal("INC-S7", "mallory", T0.plusSeconds(60)),
                HttpStatus.CONFLICT);
    }

    @Test
    void sealIdempotency_replayAndConflictAndFailureNotOccupying() {
        commanding("INC-S8", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        String commandKey = key();
        SealCreateRequest req = new SealCreateRequest(commandKey, T0.plusSeconds(60));
        SealPackageView first = sealService.seal("INC-S8", "alice", req);
        // 同键同参重放首个响应，不生成新包
        SealPackageView replay = sealService.seal("INC-S8", "alice", req);
        assertThat(replay).isEqualTo(first);
        assertThat(sealService.sealHistory("INC-S8").packages()).hasSize(1);
        // 同键改参 409
        assertApiStatus(() -> sealService.seal("INC-S8", "alice",
                new SealCreateRequest(commandKey, T0.plusSeconds(70))), HttpStatus.CONFLICT);
        // 失败不占键：先用非法 cutoff 失败，再同键以合法参数成功
        String failKey = key();
        assertApiStatus(() -> sealService.seal("INC-S8", "alice",
                new SealCreateRequest(failKey, T0.plusSeconds(500))), HttpStatus.BAD_REQUEST);
        SealPackageView v2 = sealService.seal("INC-S8", "alice",
                new SealCreateRequest(failKey, T0.plusSeconds(90)));
        assertThat(v2.version()).isEqualTo(2);
    }

    @Test
    void sealGraphCycle_422_noPartialPackage() {
        commanding("INC-S9A", "alice");
        commanding("INC-S9B", "bob");
        incidentService.createTask("INC-S9A", "alice",
                new TaskCreateRequest(key(), "T-A", "G", "任务A", List.of("INC-S9B")));
        incidentService.createTask("INC-S9B", "bob",
                new TaskCreateRequest(key(), "T-B", "G", "任务B", List.of()));
        // 绕过服务直接写入反向边，制造依赖环（模拟数据被异常修订的场景）
        Long taskBId = jdbc.queryForObject(
                "SELECT t.id FROM incident_tasks t JOIN incidents i ON i.id = t.incident_id"
                        + " WHERE i.incident_key = 'INC-S9B'", Long.class);
        Long incidentAId = jdbc.queryForObject(
                "SELECT id FROM incidents WHERE incident_key = 'INC-S9A'", Long.class);
        jdbc.update("INSERT INTO incident_task_blockers (task_id, blocker_incident_id, created_at)"
                        + " VALUES (?,?,?)", taskBId, incidentAId,
                java.sql.Timestamp.from(T0));

        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        assertThatThrownBy(() -> seal("INC-S9A", "alice", T0.plusSeconds(60)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat((List<Object>) e.details()).containsExactly("T-A->INC-S9B");
                });
        // 不生成部分包
        Integer packages = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_seal_packages", Integer.class);
        Integer entries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_seal_entries", Integer.class);
        Integer edges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_seal_edges", Integer.class);
        assertThat(packages).isZero();
        assertThat(entries).isZero();
        assertThat(edges).isZero();
    }

    @Test
    void sealDependencyEdges_snapshotImmutable() {
        commanding("INC-S10A", "alice");
        commanding("INC-S10B", "bob");
        incidentService.createTask("INC-S10A", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "任务一", List.of("INC-S10B")));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        SealPackageView v1 = seal("INC-S10A", "alice", T0.plusSeconds(60));
        assertThat(v1.edgeCount()).isEqualTo(1);
        SealPackageDetailView detail = sealService.sealDetail("INC-S10A", 1);
        assertThat(detail.edges()).hasSize(1);
        assertThat(detail.edges().get(0).taskKey()).isEqualTo("T-1");
        assertThat(detail.edges().get(0).blockerIncidentKey()).isEqualTo("INC-S10B");
        // 任务快照固化阻塞事件当时状态
        SealEntryView taskEntry = detail.entries().stream()
                .filter(e -> e.recordType().equals("TASK")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) taskEntry.payload();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blockers = (List<Map<String, Object>>) payload.get("blockers");
        assertThat(blockers).hasSize(1);
        assertThat(blockers.get(0).get("incidentKey")).isEqualTo("INC-S10B");
        assertThat(blockers.get(0).get("incidentStatus")).isEqualTo("COMMANDING");
    }

    @Test
    void appendix_mainFlowAndImmutability() {
        commanding("INC-S11", "alice");
        addAction("INC-S11", "alice", "A1", T0.plusSeconds(10));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        seal("INC-S11", "alice", T0.plusSeconds(60));

        SealAppendixView appendix = sealService.appendAppendix("INC-S11", "auditor-1",
                new SealAppendixRequest(key(), 1, "A1", "{\"note\":\"更正后内容\"}", "记录有误"));
        assertThat(appendix.recordKey()).isEqualTo("A1");
        assertThat(appendix.newValue()).isEqualTo("{\"note\":\"更正后内容\"}");
        assertThat(appendix.reason()).isEqualTo("记录有误");
        // 原值为封存快照
        @SuppressWarnings("unchecked")
        Map<String, Object> oldValue = (Map<String, Object>) appendix.oldValue();
        assertThat(oldValue.get("actionKey")).isEqualTo("A1");
        assertThat(oldValue.get("note")).isEqualTo("note-A1");

        // 原包内容不被修改
        SealPackageDetailView detail = sealService.sealDetail("INC-S11", 1);
        SealEntryView a1 = detail.entries().stream()
                .filter(e -> e.recordKey().equals("A1")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) a1.payload();
        assertThat(payload.get("note")).isEqualTo("note-A1");

        // 历史查询可见附录
        SealHistoryView history = sealService.sealHistory("INC-S11");
        assertThat(history.appendices()).hasSize(1);
        assertThat(history.appendices().get(0).recordKey()).isEqualTo("A1");
    }

    @Test
    void appendix_notFoundAndIdempotency() {
        commanding("INC-S12", "alice");
        addAction("INC-S12", "alice", "A1", T0.plusSeconds(10));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));
        seal("INC-S12", "alice", T0.plusSeconds(60));

        // 包版本不存在 404
        assertApiStatus(() -> sealService.appendAppendix("INC-S12", "auditor-1",
                new SealAppendixRequest(key(), 99, "A1", "v", "r")), HttpStatus.NOT_FOUND);
        // 记录标识不存在 404
        assertApiStatus(() -> sealService.appendAppendix("INC-S12", "auditor-1",
                new SealAppendixRequest(key(), 1, "NOPE", "v", "r")), HttpStatus.NOT_FOUND);
        // 必填校验 400
        assertApiStatus(() -> sealService.appendAppendix("INC-S12", "auditor-1",
                new SealAppendixRequest(key(), 1, "A1", " ", "r")), HttpStatus.BAD_REQUEST);
        assertApiStatus(() -> sealService.appendAppendix("INC-S12", "auditor-1",
                new SealAppendixRequest(key(), null, "A1", "v", "r")), HttpStatus.BAD_REQUEST);

        // 幂等：同键同参重放，同键改参 409
        String commandKey = key();
        SealAppendixRequest req = new SealAppendixRequest(commandKey, 1, "A1", "v1", "r1");
        SealAppendixView first = sealService.appendAppendix("INC-S12", "auditor-1", req);
        SealAppendixView replay = sealService.appendAppendix("INC-S12", "auditor-1", req);
        assertThat(replay).isEqualTo(first);
        assertThat(sealService.sealHistory("INC-S12").appendices()).hasSize(1);
        assertApiStatus(() -> sealService.appendAppendix("INC-S12", "auditor-1",
                new SealAppendixRequest(commandKey, 1, "A1", "v2", "r1")), HttpStatus.CONFLICT);
    }

    @Test
    void diagnostics_reflectsWindowAndReadOnly() {
        commanding("INC-S13", "alice");
        addAction("INC-S13", "alice", "A1", T0.plusSeconds(10));
        addAction("INC-S13", "alice", "A2", T0.plusSeconds(70));
        incidentService.createTask("INC-S13", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "任务一", List.of()));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(100));

        SealDiagnosticsView before = sealService.sealDiagnostics("INC-S13");
        assertThat(before.lastVersion()).isNull();
        assertThat(before.nextLowerBoundAt()).isEqualTo(T0);
        assertThat(before.pendingActionCount()).isEqualTo(2);
        assertThat(before.pendingStatusChangeCount()).isEqualTo(2);
        assertThat(before.taskCount()).isEqualTo(1);
        assertThat(boundaryKeys(before)).isEmpty();

        seal("INC-S13", "alice", T0.plusSeconds(60));
        SealDiagnosticsView after = sealService.sealDiagnostics("INC-S13");
        assertThat(after.lastVersion()).isEqualTo(1);
        assertThat(after.lastCutoffAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(after.nextLowerBoundAt()).isEqualTo(T0.plusSeconds(60));
        // 窗口右移后仅剩 A2 待封存
        assertThat(after.pendingActionCount()).isEqualTo(1);

        // 只读：重复读取不改变历史
        sealService.sealDiagnostics("INC-S13");
        sealService.sealDetail("INC-S13", 1);
        assertThat(sealService.sealHistory("INC-S13").packages()).hasSize(1);
    }

    private static List<String> boundaryKeys(SealDiagnosticsView view) {
        return view.boundaryConflictActionKeys();
    }

    @Test
    void sealNotFound_404() {
        assertApiStatus(() -> seal("INC-MISSING", "alice", T0), HttpStatus.NOT_FOUND);
        commanding("INC-S14", "alice");
        assertApiStatus(() -> sealService.sealDetail("INC-S14", 1), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> sealService.sealHistory("INC-MISSING"), HttpStatus.NOT_FOUND);
    }
}
