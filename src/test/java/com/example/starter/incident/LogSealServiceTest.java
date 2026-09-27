package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.SealCorrectionRequest;
import com.example.starter.incident.dto.Requests.SealLogRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.SealCorrectionView;
import com.example.starter.incident.dto.Responses.SealDetailView;
import com.example.starter.incident.dto.Responses.SealListView;
import com.example.starter.incident.dto.Responses.SealRecordView;
import com.example.starter.incident.dto.Responses.SealVerifyView;
import com.example.starter.incident.dto.Responses.SealView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 事件指挥日志封存服务测试：覆盖封存主流程、按 (时刻, 标识) 排序与版本固化、
 * 截至时刻状态还原、422/409/404 失败分支与不留部分包、不可变、更正附录、
 * commandKey 幂等以及封存与指令写入的并发提交顺序裁决。使用可控 Clock，不做真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class LogSealServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private LogSealService sealService;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_log_corrections");
        jdbc.update("DELETE FROM incident_log_seal_records");
        jdbc.update("DELETE FROM incident_log_seals");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String ck() {
        return "CMD-" + UUID.randomUUID();
    }

    private void setClock(Instant at) {
        ((ControllableClock) clock).setInstant(at);
    }

    private long commanding(String incidentKey, String commander) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "封存场景", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(ck()));
        return jdbc.queryForObject("SELECT id FROM incidents WHERE incident_key = ?",
                Long.class, incidentKey);
    }

    private void action(String incidentKey, String actionKey, Instant occurredAt) {
        incidentService.addAction(incidentKey, "alice",
                new ActionRequest(ck(), actionKey, "NOTE", "说明-" + actionKey, occurredAt));
    }

    private long taskId(long incidentId, String taskKey) {
        return jdbc.queryForObject(
                "SELECT id FROM incident_tasks WHERE incident_id = ? AND task_key = ?",
                Long.class, incidentId, taskKey);
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private static void assertApiCode(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    private static ApiException catchApi(ThrowingCallable call) {
        try {
            call.call();
        } catch (ApiException e) {
            return e;
        } catch (Throwable t) {
            throw new AssertionError("期望抛出 ApiException，实际为 " + t, t);
        }
        throw new AssertionError("期望抛出 ApiException，但未抛出");
    }

    @Test
    void seal_mainFlow_sortsByTimeThenKey_andFreezesVersionsAndEdges() {
        commanding("INC-S1", "alice");

        setClock(T0.plus(10, ChronoUnit.SECONDS));
        incidentService.createTask("INC-S1", "alice",
                new TaskCreateRequest(ck(), "TK1", "G1", "任务一", List.of()));

        // 阻塞事件必须存在；带阻塞边的任务与边同刻，按 recordKey 排序 edge 在 task 前
        incidentService.report(new ReportRequest("INC-S1-B", "S3", "阻塞事件", "r"));
        setClock(T0.plus(40, ChronoUnit.SECONDS));
        incidentService.createTask("INC-S1", "alice",
                new TaskCreateRequest(ck(), "TK2", "G1", "任务二", List.of("INC-S1-B")));

        action("INC-S1", "ACT1", T0.plus(20, ChronoUnit.SECONDS));

        setClock(T0.plus(30, ChronoUnit.SECONDS));
        incidentService.initiateTransfer("INC-S1", "alice",
                new com.example.starter.incident.dto.Requests.TransferRequest(ck(), "bob"));

        setClock(T0.plus(100, ChronoUnit.SECONDS));
        SealView seal = sealService.seal("INC-S1", "alice",
                new SealLogRequest(ck(), T0.plus(100, ChronoUnit.SECONDS)));

        assertThat(seal.version()).isEqualTo(1);
        assertThat(seal.edgeCount()).isEqualTo(1);
        // 4 条业务记录：任务2 条 + 指令 1 条 + 交接 1 条
        assertThat(seal.recordCount()).isEqualTo(4);
        assertThat(seal.records()).hasSize(5);
        assertThat(seal.contentHash()).hasSize(64);

        List<SealRecordView> records = seal.records();
        assertThat(records).extracting(SealRecordView::seq).containsExactly(1, 2, 3, 4, 5);
        // 时刻顺序：TK1@10, ACT1@20, transfer@30, TK2 与 edge@40（同刻 edge 键在前）
        long s1Id = jdbc.queryForObject("SELECT id FROM incidents WHERE incident_key = ?",
                Long.class, "INC-S1");
        long blockerId = jdbc.queryForObject(
                "SELECT id FROM incidents WHERE incident_key = ?", Long.class, "INC-S1-B");
        assertThat(records).extracting(SealRecordView::recordKey).containsExactly(
                "task:TK1", "action:ACT1", records.get(2).recordKey(),
                "edge:" + taskId(s1Id, "TK2") + ":" + blockerId, "task:TK2");
        assertThat(records.get(2).recordType()).isEqualTo("TRANSFER");
        assertThat(records.get(3).recordType()).isEqualTo("TASK_BLOCKER_EDGE");
        // 同类型版本序号各自从 1 递增
        assertThat(records.get(0).recordVersion()).isEqualTo(1);
        assertThat(records.get(1).recordVersion()).isEqualTo(1);
        assertThat(records.get(2).recordVersion()).isEqualTo(1);
        assertThat(records.get(3).recordVersion()).isEqualTo(1);
        assertThat(records.get(4).recordVersion()).isEqualTo(2);
        // 边条目固化任务与阻塞事件引用
        assertThat(records.get(3).taskId()).isNotNull();
        assertThat(records.get(3).blockerIncidentId()).isNotNull();
        assertThat(records.get(3).payload().get("blockerIncidentKey").asText())
                .isEqualTo("INC-S1-B");
        // 非任务条目 taskId/blocker 为空语义稳定
        assertThat(records.get(1).taskId()).isNull();
        assertThat(records.get(1).blockerIncidentId()).isNull();

        SealVerifyView verify = sealService.verifySeal("INC-S1", 1);
        assertThat(verify.verified()).isTrue();
        assertThat(verify.storedHash()).isEqualTo(verify.recomputedHash());
    }

    @Test
    void seal_versionsIncrement_andBoundaryIsHalfOpen() {
        commanding("INC-S2", "alice");
        action("INC-S2", "EARLY", T0.plus(10, ChronoUnit.SECONDS));

        setClock(T0.plus(50, ChronoUnit.SECONDS));
        SealView v1 = sealService.seal("INC-S2", "alice",
                new SealLogRequest(ck(), T0.plus(50, ChronoUnit.SECONDS)));
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.records()).extracting(SealRecordView::recordKey)
                .containsExactly("action:EARLY");

        // 左闭右开：发生时刻恰为 v1 截至时刻的指令不在 v1 内
        action("INC-S2", "ON_BOUNDARY", T0.plus(50, ChronoUnit.SECONDS));
        action("INC-S2", "LATER", T0.plus(60, ChronoUnit.SECONDS));
        setClock(T0.plus(100, ChronoUnit.SECONDS));
        SealView v2 = sealService.seal("INC-S2", "alice",
                new SealLogRequest(ck(), T0.plus(100, ChronoUnit.SECONDS)));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.recordCount()).isEqualTo(3);

        SealListView list = sealService.listSeals("INC-S2");
        assertThat(list.seals()).extracting(SealView::version).containsExactly(1, 2);
        // 列表视图不带明细
        assertThat(list.seals().get(0).records()).isEmpty();
        // v1 详情保持只有早于边界的 1 条
        assertThat(sealService.getSeal("INC-S2", 1).seal().records()).hasSize(1);
    }

    @Test
    void seal_restoresTaskAndTransferStateAtCutoff() {
        commanding("INC-S3", "alice");
        setClock(T0.plus(10, ChronoUnit.SECONDS));
        incidentService.createTask("INC-S3", "alice",
                new TaskCreateRequest(ck(), "TK", "G", "t", List.of()));
        setClock(T0.plus(40, ChronoUnit.SECONDS));
        incidentService.initiateTransfer("INC-S3", "alice",
                new com.example.starter.incident.dto.Requests.TransferRequest(ck(), "bob"));
        setClock(T0.plus(50, ChronoUnit.SECONDS));
        incidentService.acceptTransfer("INC-S3", "bob",
                new com.example.starter.incident.dto.Requests.TransferAcceptRequest(ck()));
        setClock(T0.plus(60, ChronoUnit.SECONDS));
        incidentService.completeTask("INC-S3", "TK", "bob",
                new com.example.starter.incident.dto.Requests.TaskActionRequest(ck()));

        // 截止 45s：交接已发起未接受 → PENDING；任务未完成 → OPEN
        SealView at45 = sealService.seal("INC-S3", "bob",
                new SealLogRequest(ck(), T0.plus(45, ChronoUnit.SECONDS)));
        assertThat(at45.records()).filteredOn(r -> r.recordType().equals("TRANSFER"))
                .singleElement().satisfies(r ->
                        assertThat(r.payload().get("status").asText()).isEqualTo("PENDING"));
        assertThat(at45.records()).filteredOn(r -> r.recordType().equals("TASK_STATUS"))
                .singleElement().satisfies(r -> {
                    assertThat(r.payload().get("status").asText()).isEqualTo("OPEN");
                    assertThat(r.payload().get("doneAt").isNull()).isTrue();
                });

        // 截止 100s：接受与完成均已发生 → ACCEPTED / DONE 且终态字段非空
        setClock(T0.plus(100, ChronoUnit.SECONDS));
        SealView at100 = sealService.seal("INC-S3", "bob",
                new SealLogRequest(ck(), T0.plus(100, ChronoUnit.SECONDS)));
        assertThat(at100.records()).filteredOn(r -> r.recordType().equals("TRANSFER"))
                .singleElement().satisfies(r ->
                        assertThat(r.payload().get("status").asText()).isEqualTo("ACCEPTED"));
        assertThat(at100.records()).filteredOn(r -> r.recordType().equals("TASK_STATUS"))
                .singleElement().satisfies(r -> {
                    assertThat(r.payload().get("status").asText()).isEqualTo("DONE");
                    assertThat(r.payload().get("doneBy").asText()).isEqualTo("bob");
                    assertThat(r.payload().get("doneAt").isNull()).isFalse();
                });
    }

    @Test
    void seal_cutoffBeforeCreatedOrAfterNow_unprocessable() {
        commanding("INC-S4", "alice");
        setClock(T0.plus(100, ChronoUnit.SECONDS));
        // 早于事件创建（事件创建于 T0）
        assertApiCode(() -> sealService.seal("INC-S4", "alice",
                new SealLogRequest(ck(), T0.minusSeconds(1))),
                HttpStatus.UNPROCESSABLE_ENTITY, "CUTOFF_OUT_OF_RANGE");
        // 晚于当前时钟
        assertApiCode(() -> sealService.seal("INC-S4", "alice",
                new SealLogRequest(ck(), T0.plus(200, ChronoUnit.SECONDS))),
                HttpStatus.UNPROCESSABLE_ENTITY, "CUTOFF_OUT_OF_RANGE");
        assertThat(sealService.listSeals("INC-S4").seals()).isEmpty();
    }

    @Test
    void seal_commandAtCutoff_conflictsWithKeys() {
        commanding("INC-S5", "alice");
        action("INC-S5", "AT50", T0.plus(50, ChronoUnit.SECONDS));
        setClock(T0.plus(100, ChronoUnit.SECONDS));
        ApiException conflict = catchApi(() -> sealService.seal("INC-S5", "alice",
                new SealLogRequest(ck(), T0.plus(50, ChronoUnit.SECONDS))));
        assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.details()).isNotNull();
        // 选择其他截至时刻（跨过同刻）后成功，且该指令入包
        SealView seal = sealService.seal("INC-S5", "alice",
                new SealLogRequest(ck(), T0.plus(51, ChronoUnit.SECONDS)));
        assertThat(seal.records()).extracting(SealRecordView::recordKey)
                .contains("action:AT50");
    }

    @Test
    void seal_closedIncident_notAllowed_butQueriesAndCorrectionsRemain() {
        commanding("INC-S6", "alice");
        action("INC-S6", "A1", T0.plus(10, ChronoUnit.SECONDS));
        setClock(T0.plus(20, ChronoUnit.SECONDS));
        SealView v1 = sealService.seal("INC-S6", "alice",
                new SealLogRequest(ck(), T0.plus(20, ChronoUnit.SECONDS)));
        incidentService.changeStatus("INC-S6", "alice", new StatusRequest(ck(), "CONTAINED"));
        incidentService.changeStatus("INC-S6", "alice", new StatusRequest(ck(), "RESOLVED"));
        incidentService.changeStatus("INC-S6", "alice", new StatusRequest(ck(), "CLOSED"));

        // 关闭后不能新增完整封存包
        setClock(T0.plus(100, ChronoUnit.SECONDS));
        assertApiCode(() -> sealService.seal("INC-S6", "alice",
                new SealLogRequest(ck(), T0.plus(100, ChronoUnit.SECONDS))),
                HttpStatus.UNPROCESSABLE_ENTITY, "SEAL_NOT_ALLOWED");
        // 只读查询仍可用
        assertThat(sealService.getSeal("INC-S6", 1).seal().contentHash())
                .isEqualTo(v1.contentHash());
        // 关闭事件仍可追加更正附录
        SealCorrectionView correction = sealService.appendCorrection("INC-S6", 1, "auditor",
                new SealCorrectionRequest(ck(), "action:A1", "note", "说明-A1", "更正后说明",
                        "事后核对事实有误"));
        assertThat(correction.sealVersion()).isEqualTo(1);
        assertThat(correction.correctedBy()).isEqualTo("auditor");
        assertThat(sealService.getSeal("INC-S6", 1).corrections()).hasSize(1);
    }

    @Test
    void seal_graphCycle_unprocessableAndNoPartialPackage() {
        long incidentId = commanding("INC-S7", "alice");
        setClock(T0.plus(10, ChronoUnit.SECONDS));
        incidentService.createTask("INC-S7", "alice",
                new TaskCreateRequest(ck(), "TK", "G", "t", List.of()));
        long tkId = taskId(incidentId, "TK");
        // 模拟外部改图/事件合并引入自环：本事件任务直接阻塞本事件
        jdbc.update("INSERT INTO incident_task_blockers (task_id, blocker_incident_id, created_at)"
                + " VALUES (?,?,?)", tkId, incidentId,
                java.sql.Timestamp.from(T0.plus(15, ChronoUnit.SECONDS)));

        setClock(T0.plus(100, ChronoUnit.SECONDS));
        assertApiCode(() -> sealService.seal("INC-S7", "alice",
                new SealLogRequest(ck(), T0.plus(100, ChronoUnit.SECONDS))),
                HttpStatus.UNPROCESSABLE_ENTITY, "SEAL_GRAPH_CYCLE");
        // 失败不留部分包
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_log_seals", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM incident_log_seal_records",
                Integer.class)).isZero();
    }

    @Test
    void seal_andQueries_notFound() {
        commanding("INC-S8", "alice");
        assertApiStatus(() -> sealService.seal("INC-NOPE", "alice",
                new SealLogRequest(ck(), T0)), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> sealService.getSeal("INC-NOPE", 1), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> sealService.verifySeal("INC-S8", 9), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> sealService.appendCorrection("INC-S8", 9, "alice",
                new SealCorrectionRequest(ck(), "action:x", "note", "a", "b", "r")),
                HttpStatus.NOT_FOUND);
    }

    @Test
    void sealedPackage_immutableAgainstLaterTaskRevisionAndUndo() {
        long incidentId = commanding("INC-S9", "alice");
        incidentService.report(new ReportRequest("INC-S9-B", "S3", "阻塞事件", "r"));
        long blockerId = jdbc.queryForObject(
                "SELECT id FROM incidents WHERE incident_key = ?", Long.class, "INC-S9-B");
        setClock(T0.plus(10, ChronoUnit.SECONDS));
        incidentService.createTask("INC-S9", "alice",
                new TaskCreateRequest(ck(), "TK", "G", "原始标题", List.of("INC-S9-B")));
        action("INC-S9", "A1", T0.plus(20, ChronoUnit.SECONDS));
        setClock(T0.plus(50, ChronoUnit.SECONDS));
        SealDetailView before = sealService.getSeal("INC-S9",
                sealService.seal("INC-S9", "alice",
                        new SealLogRequest(ck(), T0.plus(50, ChronoUnit.SECONDS))).version());
        String hashBefore = before.seal().contentHash();
        String payloadBefore = before.seal().records().get(0).payload().toString();

        // 封存后：解除阻塞、完成任务、新增指令、阻塞事件被遏制——均不得改写原包
        incidentService.takeover("INC-S9-B", "r", new TakeoverRequest(ck()));
        incidentService.changeStatus("INC-S9-B", "r", new StatusRequest(ck(), "CONTAINED"));
        setClock(T0.plus(60, ChronoUnit.SECONDS));
        incidentService.completeTask("INC-S9", "TK", "alice",
                new com.example.starter.incident.dto.Requests.TaskActionRequest(ck()));
        action("INC-S9", "A2", T0.plus(70, ChronoUnit.SECONDS));

        SealDetailView after = sealService.getSeal("INC-S9", 1);
        assertThat(after.seal().contentHash()).isEqualTo(hashBefore);
        assertThat(after.seal().records()).hasSize(before.seal().records().size());
        assertThat(after.seal().records().get(0).payload().toString()).isEqualTo(payloadBefore);
        assertThat(after.seal().records()).filteredOn(r -> r.recordKey().equals("action:A2"))
                .isEmpty();
        assertThat(sealService.verifySeal("INC-S9", 1).verified()).isTrue();
        // 边引用仍指向原阻塞事件 id，不随后续操作改写
        assertThat(after.seal().records())
                .filteredOn(r -> r.recordType().equals("TASK_BLOCKER_EDGE"))
                .singleElement().satisfies(r ->
                        assertThat(r.blockerIncidentId()).isEqualTo(blockerId));
        assertThat(incidentId).isPositive();
    }

    @Test
    void correction_validationAndIdempotency() {
        commanding("INC-S10", "alice");
        action("INC-S10", "A1", T0.plus(10, ChronoUnit.SECONDS));
        setClock(T0.plus(50, ChronoUnit.SECONDS));
        sealService.seal("INC-S10", "alice", new SealLogRequest(ck(), T0.plus(50, ChronoUnit.SECONDS)));

        // 原记录标识不存在：422
        assertApiCode(() -> sealService.appendCorrection("INC-S10", 1, "alice",
                new SealCorrectionRequest(ck(), "action:MISSING", "note", "a", "b", "r")),
                HttpStatus.UNPROCESSABLE_ENTITY, "CORRECTION_TARGET_NOT_FOUND");
        // 失败不占键：同一 commandKey 随后用于合法更正应成功
        String commandKey = ck();
        SealCorrectionView first = sealService.appendCorrection("INC-S10", 1, "alice",
                new SealCorrectionRequest(commandKey, "action:A1", "note", "旧", "新", "理由"));
        SealCorrectionView replay = sealService.appendCorrection("INC-S10", 1, "alice",
                new SealCorrectionRequest(commandKey, "action:A1", "note", "旧", "新", "理由"));
        assertThat(replay).isEqualTo(first);
        assertThat(sealService.getSeal("INC-S10", 1).corrections()).hasSize(1);
        // 同键异参 409
        assertApiStatus(() -> sealService.appendCorrection("INC-S10", 1, "alice",
                new SealCorrectionRequest(commandKey, "action:A1", "note", "旧", "不同", "理由")),
                HttpStatus.CONFLICT);
        // null 原值语义保留
        SealCorrectionView nullOld = sealService.appendCorrection("INC-S10", 1, "alice",
                new SealCorrectionRequest(ck(), "action:A1", "note", null, "补录", "原值为空"));
        assertThat(nullOld.oldValue()).isNull();
        assertThat(nullOld.newValue()).isEqualTo("补录");
    }

    @Test
    void seal_commandKeyIdempotency_replaySameParams_conflictOnDifferentParams() {
        commanding("INC-S11", "alice");
        action("INC-S11", "A1", T0.plus(10, ChronoUnit.SECONDS));
        setClock(T0.plus(50, ChronoUnit.SECONDS));

        String commandKey = ck();
        SealLogRequest req = new SealLogRequest(commandKey, T0.plus(50, ChronoUnit.SECONDS));
        SealView first = sealService.seal("INC-S11", "alice", req);
        SealView replay = sealService.seal("INC-S11", "alice", req);
        assertThat(replay).isEqualTo(first);
        // 重放不产生第二版
        assertThat(sealService.listSeals("INC-S11").seals()).hasSize(1);
        // 同键异参 409
        assertApiStatus(() -> sealService.seal("INC-S11", "alice",
                new SealLogRequest(commandKey, T0.plus(40, ChronoUnit.SECONDS))),
                HttpStatus.CONFLICT);

        // 失败不占键：新键先用于越界截止时刻失败，再用于合法请求成功
        String retryKey = ck();
        assertApiStatus(() -> sealService.seal("INC-S11", "alice",
                new SealLogRequest(retryKey, T0.plus(500, ChronoUnit.SECONDS))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        action("INC-S11", "A2", T0.plus(60, ChronoUnit.SECONDS));
        setClock(T0.plus(100, ChronoUnit.SECONDS));
        SealView second = sealService.seal("INC-S11", "alice",
                new SealLogRequest(retryKey, T0.plus(100, ChronoUnit.SECONDS)));
        assertThat(second.version()).isEqualTo(2);
    }

    @Test
    void concurrentSeals_assignDistinctVersions() throws Exception {
        commanding("INC-S12", "alice");
        action("INC-S12", "A1", T0.plus(10, ChronoUnit.SECONDS));
        setClock(T0.plus(100, ChronoUnit.SECONDS));
        List<Callable<SealView>> tasks = List.of(
                () -> sealService.seal("INC-S12", "alice",
                        new SealLogRequest(ck(), T0.plus(60, ChronoUnit.SECONDS))),
                () -> sealService.seal("INC-S12", "alice",
                        new SealLogRequest(ck(), T0.plus(100, ChronoUnit.SECONDS))));
        List<SealView> results = runConcurrently(tasks).stream()
                .filter(SealView.class::isInstance).map(SealView.class::cast).toList();
        assertThat(results).hasSize(2);
        assertThat(results).extracting(SealView::version).containsExactlyInAnyOrder(1, 2);
        // 唯一约束兜底：无重复版本
        Integer distinctVersions = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT version) FROM incident_log_seals WHERE incident_id ="
                        + " (SELECT id FROM incidents WHERE incident_key = 'INC-S12')",
                Integer.class);
        assertThat(distinctVersions).isEqualTo(2);
    }

    @Test
    void concurrentSealAndCommand_commitOrderAdjudicatesBoundary() throws Exception {
        commanding("INC-S13", "alice");
        Instant boundary = T0.plus(50, ChronoUnit.SECONDS);
        setClock(T0.plus(100, ChronoUnit.SECONDS));

        // 同一事件：封存截止时刻 C 与发生时刻恰为 C 的指令并发，均锁事件行串行提交。
        List<Object> results = runConcurrently(List.of(
                () -> sealService.seal("INC-S13", "alice", new SealLogRequest(ck(), boundary)),
                () -> {
                    action("INC-S13", "AT_C", boundary);
                    return "COMMAND_OK";
                }));

        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
        long sealsCreated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_log_seals s JOIN incidents i ON i.id = s.incident_id"
                        + " WHERE i.incident_key = 'INC-S13'", Integer.class);
        if (conflicts == 1) {
            // 指令先提交：封存遇同刻 409，无包；指令保留
            assertThat(sealsCreated).isZero();
            assertThat(incidentService.history("INC-S13").actions()).hasSize(1);
        } else {
            // 封存先提交：封存成功（边界内无同刻记录），指令后提交也成功，且不在 v1 包内
            assertThat(results).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
            assertThat(sealsCreated).isEqualTo(1);
            assertThat(sealService.getSeal("INC-S13", 1).seal().records()).isEmpty();
            assertThat(incidentService.history("INC-S13").actions()).hasSize(1);
        }
    }

    /**
     * 并发执行一批任务并收集成功值或异常（使用闭锁协调真实同时发起，带超时）。
     */
    private static <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
