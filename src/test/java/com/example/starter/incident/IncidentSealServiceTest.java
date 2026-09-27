package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.AppendixRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.SealCreateRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.AppendixView;
import com.example.starter.incident.dto.Responses.SealDetailView;
import com.example.starter.incident.dto.Responses.SealDiagnosticsView;
import com.example.starter.incident.dto.Responses.SealRecordView;
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
 * 日志封存与更正附录服务测试：覆盖封存主流程、截至时刻边界（左闭右开）、
 * 同刻未完成写入 409、依赖图违规 422 无部分包、包不可变性、版本递增、
 * 附录追加与校验、已关闭事件边界及 commandKey 幂等语义。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class IncidentSealServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentSealService sealService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_log_appendices");
        jdbc.update("DELETE FROM incident_log_seal_edges");
        jdbc.update("DELETE FROM incident_log_seal_records");
        jdbc.update("DELETE FROM incident_log_seals");
        jdbc.update("DELETE FROM command_keys");
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
        incidentService.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        incidentService.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void addAction(String incidentKey, String actor, String actionKey, Instant occurredAt) {
        incidentService.addAction(incidentKey, actor,
                new ActionRequest(key(), actionKey, "NOTE", "记录-" + actionKey, occurredAt));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void seal_mainFlow_capturesActionsTransfersTasksAndEdges() {
        commanding("INC-S1", "alice");
        commanding("INC-S1-BLOCKER", "carol");
        addAction("INC-S1", "alice", "ACT-2", T0.plusSeconds(20));
        addAction("INC-S1", "alice", "ACT-1", T0.plusSeconds(10));
        incidentService.initiateTransfer("INC-S1", "alice", new TransferRequest(key(), "bob"));
        incidentService.acceptTransfer("INC-S1", "bob", new TransferAcceptRequest(key()));
        incidentService.createTask("INC-S1", "bob", new TaskCreateRequest(key(), "T-1", "G1",
                "切换备用链路", java.util.List.of("INC-S1-BLOCKER")));

        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
        SealView seal = sealService.createSeal("INC-S1", "bob",
                new SealCreateRequest(key(), T0.plusSeconds(1000)));

        assertThat(seal.version()).isEqualTo(1);
        assertThat(seal.sealUntil()).isEqualTo(T0.plusSeconds(1000));
        assertThat(seal.sealedBy()).isEqualTo("bob");
        assertThat(seal.recordCount()).isEqualTo(4);
        assertThat(seal.edgeCount()).isEqualTo(1);

        SealDetailView detail = sealService.getSeal("INC-S1", 1);
        // 排序：发生时刻升序，同刻按记录标识；交接与任务创建于 T0，指令按 occurredAt
        assertThat(detail.records()).extracting(SealRecordView::recordType)
                .containsExactly("TRANSFER", "TASK", "ACTION", "ACTION");
        assertThat(detail.records()).extracting(SealRecordView::seq)
                .containsExactly(0, 1, 2, 3);
        assertThat(detail.records().get(0).recordVersion()).isEqualTo(2); // 交接已接受
        assertThat(detail.records().get(1).recordKey()).isEqualTo("T-1");
        assertThat(detail.records().get(1).payload().get("status").asText()).isEqualTo("OPEN");
        assertThat(detail.records().get(2).recordKey()).isEqualTo("ACT-1");
        assertThat(detail.records().get(2).payload().get("note").asText()).isEqualTo("记录-ACT-1");
        assertThat(detail.records().get(3).recordKey()).isEqualTo("ACT-2");
        assertThat(detail.edges()).hasSize(1);
        assertThat(detail.edges().get(0).taskKey()).isEqualTo("T-1");
        assertThat(detail.edges().get(0).blockerIncidentKey()).isEqualTo("INC-S1-BLOCKER");
    }

    @Test
    void seal_leftClosedRightOpen_excludesRecordsAtCutoff() {
        commanding("INC-S2", "alice");
        addAction("INC-S2", "alice", "ACT-EARLY", T0.plusSeconds(10));
        addAction("INC-S2", "alice", "ACT-AT", T0.plusSeconds(50));

        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
        SealView seal = sealService.createSeal("INC-S2", "alice",
                new SealCreateRequest(key(), T0.plusSeconds(50)));

        assertThat(seal.recordCount()).isEqualTo(1);
        SealDetailView detail = sealService.getSeal("INC-S2", 1);
        assertThat(detail.records()).extracting(SealRecordView::recordKey)
                .containsExactly("ACT-EARLY");
    }

    @Test
    void seal_sameInstantRecords_orderedByRecordKey() {
        commanding("INC-S3", "alice");
        addAction("INC-S3", "alice", "ACT-B", T0.plusSeconds(10));
        addAction("INC-S3", "alice", "ACT-A", T0.plusSeconds(10));

        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
        sealService.createSeal("INC-S3", "alice", new SealCreateRequest(key(), T0.plusSeconds(100)));

        SealDetailView detail = sealService.getSeal("INC-S3", 1);
        assertThat(detail.records()).extracting(SealRecordView::recordKey)
                .containsExactly("ACT-A", "ACT-B");
    }

    @Test
    void seal_cutoffOutOfRange_badRequestWithActualAndRequired() {
        commanding("INC-S4", "alice");
        // 早于事件创建时刻（T0）
        assertThatThrownBy(() -> sealService.createSeal("INC-S4", "alice",
                new SealCreateRequest(key(), T0.minusSeconds(1))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains(T0.minusSeconds(1).toString())
                            .contains(T0.toString());
                });
        // 晚于当前时刻（时钟在 T0）
        assertThatThrownBy(() -> sealService.createSeal("INC-S4", "alice",
                new SealCreateRequest(key(), T0.plusSeconds(60))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getMessage()).contains(T0.plusSeconds(60).toString())
                            .contains(T0.toString());
                });
        // sealUntil 为空
        assertApiStatus(() -> sealService.createSeal("INC-S4", "alice",
                new SealCreateRequest(key(), null)), HttpStatus.BAD_REQUEST);
        assertThat(sealService.listSeals("INC-S4").seals()).isEmpty();
    }

    @Test
    void seal_inFlightSameInstantCommand_conflict_thenOtherCutoffSucceeds() {
        commanding("INC-S5", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
        Instant cutoff = T0.plusSeconds(500);
        // 模拟同刻尚未完成的指令写入（占位未补写响应）
        jdbc.update("INSERT INTO command_keys (command_key, operation, request_hash,"
                        + " response_status, response_body, created_at) VALUES (?,?,?,NULL,NULL,?)",
                "CMD-IN-FLIGHT", "action", "hash-x", Timestamp.from(cutoff));

        assertThatThrownBy(() -> sealService.createSeal("INC-S5", "alice",
                new SealCreateRequest(key(), cutoff)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("1").contains(cutoff.toString());
                });
        assertThat(sealService.listSeals("INC-S5").seals()).isEmpty();

        // 选择其他截至时刻可成功封存
        SealView seal = sealService.createSeal("INC-S5", "alice",
                new SealCreateRequest(key(), T0.plusSeconds(600)));
        assertThat(seal.version()).isEqualTo(1);
    }

    @Test
    void seal_cycleInDependencyGraph_unprocessableAndNoPartialPackage() {
        commanding("INC-S6", "alice");
        commanding("INC-S6-OTHER", "bob");
        incidentService.createTask("INC-S6", "alice",
                new TaskCreateRequest(key(), "T-A", "G1", "任务A", null));
        incidentService.createTask("INC-S6-OTHER", "bob",
                new TaskCreateRequest(key(), "T-B", "G1", "任务B", null));
        // 直接注入双向依赖边，绕过创建时环检测，构造损坏的依赖图
        Long taskA = jdbc.queryForObject(
                "SELECT id FROM incident_tasks WHERE task_key = 'T-A'", Long.class);
        Long taskB = jdbc.queryForObject(
                "SELECT id FROM incident_tasks WHERE task_key = 'T-B'", Long.class);
        Long otherId = jdbc.queryForObject(
                "SELECT id FROM incidents WHERE incident_key = 'INC-S6-OTHER'", Long.class);
        Long selfId = jdbc.queryForObject(
                "SELECT id FROM incidents WHERE incident_key = 'INC-S6'", Long.class);
        jdbc.update("INSERT INTO incident_task_blockers (task_id, blocker_incident_id, created_at)"
                + " VALUES (?,?,?)", taskA, otherId, Timestamp.from(T0));
        jdbc.update("INSERT INTO incident_task_blockers (task_id, blocker_incident_id, created_at)"
                + " VALUES (?,?,?)", taskB, selfId, Timestamp.from(T0));

        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
        assertApiStatus(() -> sealService.createSeal("INC-S6", "alice",
                new SealCreateRequest(key(), T0.plusSeconds(1000))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 不生成部分包
        assertThat(sealService.listSeals("INC-S6").seals()).isEmpty();
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_log_seal_records", Integer.class);
        assertThat(rows).isZero();
    }

    @Test
    void seal_immutable_afterTaskRevisionAndNewActions() {
        commanding("INC-S7", "alice");
        incidentService.createTask("INC-S7", "alice",
                new TaskCreateRequest(key(), "T-1", "G1", "初版任务", null));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
        sealService.createSeal("INC-S7", "alice", new SealCreateRequest(key(), T0.plusSeconds(1000)));

        // 封存后修订任务、追加指令
        incidentService.completeTask("INC-S7", "T-1", "alice", new TaskActionRequest(key()));
        addAction("INC-S7", "alice", "ACT-NEW", T0.plusSeconds(1500));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(2000));
        SealView v2 = sealService.createSeal("INC-S7", "alice",
                new SealCreateRequest(key(), T0.plusSeconds(2000)));
        assertThat(v2.version()).isEqualTo(2);

        // 原包内容不被改写：v1 中任务仍为 OPEN、版本 1，且不包含新指令
        SealDetailView v1 = sealService.getSeal("INC-S7", 1);
        assertThat(v1.records()).hasSize(1);
        assertThat(v1.records().get(0).payload().get("status").asText()).isEqualTo("OPEN");
        assertThat(v1.records().get(0).recordVersion()).isEqualTo(1);
        // v2 固化新状态：任务 DONE、版本 2，包含新指令
        SealDetailView v2Detail = sealService.getSeal("INC-S7", 2);
        assertThat(v2Detail.records()).hasSize(2);
        SealRecordView taskRecord = v2Detail.records().stream()
                .filter(r -> r.recordKey().equals("T-1")).findFirst().orElseThrow();
        assertThat(taskRecord.payload().get("status").asText()).isEqualTo("DONE");
        assertThat(taskRecord.recordVersion()).isEqualTo(2);
    }

    @Test
    void seal_closedIncident_illegalTransition_butAppendixStillAllowed() {
        commanding("INC-S8", "alice");
        addAction("INC-S8", "alice", "ACT-1", T0.plusSeconds(10));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
        sealService.createSeal("INC-S8", "alice", new SealCreateRequest(key(), T0.plusSeconds(100)));

        incidentService.changeStatus("INC-S8", "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus("INC-S8", "alice", new StatusRequest(key(), "RESOLVED"));
        incidentService.changeStatus("INC-S8", "alice", new StatusRequest(key(), "CLOSED"));

        // 已关闭事件不得新增封存包
        assertApiStatus(() -> sealService.createSeal("INC-S8", "alice",
                new SealCreateRequest(key(), T0.plusSeconds(1000))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 已关闭事件允许查询与追加更正附录
        assertThat(sealService.getSeal("INC-S8", 1).records()).hasSize(1);
        AppendixView appendix = sealService.appendAppendix("INC-S8", "alice",
                new AppendixRequest(key(), 1, "ACT-1", "note", "记录-ACT-1", "更正后说明",
                        "事实错误更正"));
        assertThat(appendix.sealVersion()).isEqualTo(1);
        assertThat(appendix.newValue()).isEqualTo("更正后说明");
    }

    @Test
    void seal_commandKeyReplay_conflictAndFailureNotOccupying() {
        commanding("INC-S9", "alice");
        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
        String commandKey = key();
        SealView first = sealService.createSeal("INC-S9", "alice",
                new SealCreateRequest(commandKey, T0.plusSeconds(100)));
        // 同键同参重放首个响应，不生成新包
        SealView replay = sealService.createSeal("INC-S9", "alice",
                new SealCreateRequest(commandKey, T0.plusSeconds(100)));
        assertThat(replay).isEqualTo(first);
        assertThat(sealService.listSeals("INC-S9").seals()).hasSize(1);
        // 同键异参 409
        assertApiStatus(() -> sealService.createSeal("INC-S9", "alice",
                new SealCreateRequest(commandKey, T0.plusSeconds(200))), HttpStatus.CONFLICT);
        // 失败不占键：先用 K2 触发 400，再用 K2 成功封存
        String failedKey = key();
        assertApiStatus(() -> sealService.createSeal("INC-S9", "alice",
                new SealCreateRequest(failedKey, T0.plusSeconds(5000))), HttpStatus.BAD_REQUEST);
        SealView recovered = sealService.createSeal("INC-S9", "alice",
                new SealCreateRequest(failedKey, T0.plusSeconds(300)));
        assertThat(recovered.version()).isEqualTo(2);
    }

    @Test
    void appendix_mainFlow_nullOldValue_andValidationFailures() {
        commanding("INC-S10", "alice");
        addAction("INC-S10", "alice", "ACT-1", T0.plusSeconds(10));
        incidentService.initiateTransfer("INC-S10", "alice", new TransferRequest(key(), "bob"));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
        sealService.createSeal("INC-S10", "alice", new SealCreateRequest(key(), T0.plusSeconds(100)));
        SealDetailView detail = sealService.getSeal("INC-S10", 1);
        String transferKey = detail.records().stream()
                .filter(r -> r.recordType().equals("TRANSFER")).findFirst().orElseThrow()
                .recordKey();

        // 主流程：更正指令 note
        AppendixView appendix = sealService.appendAppendix("INC-S10", "alice",
                new AppendixRequest(key(), 1, "ACT-1", "note", "记录-ACT-1", "实际为扩容操作",
                        "原始记录描述错误"));
        assertThat(appendix.oldValue()).isEqualTo("记录-ACT-1");
        assertThat(appendix.newValue()).isEqualTo("实际为扩容操作");
        assertThat(appendix.reason()).isEqualTo("原始记录描述错误");
        assertThat(appendix.operator()).isEqualTo("alice");
        // null 语义：交接 acceptedAt 为 null，提交 oldValue=null 可更正
        AppendixView nullAppendix = sealService.appendAppendix("INC-S10", "alice",
                new AppendixRequest(key(), 1, transferKey, "acceptedAt", null,
                        "2026-09-22T00:00:30Z", "补录接受时刻"));
        assertThat(nullAppendix.oldValue()).isNull();
        // 原包不被改写
        SealDetailView after = sealService.getSeal("INC-S10", 1);
        assertThat(after.records().stream().filter(r -> r.recordKey().equals("ACT-1"))
                .findFirst().orElseThrow().payload().get("note").asText()).isEqualTo("记录-ACT-1");
        assertThat(after.appendices()).hasSize(2);
        assertThat(sealService.listAppendices("INC-S10").appendices()).hasSize(2);

        // 原值不一致 409，响应含实际值与提交值
        assertThatThrownBy(() -> sealService.appendAppendix("INC-S10", "alice",
                new AppendixRequest(key(), 1, "ACT-1", "note", "错误的原值", "x", "r")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).contains("记录-ACT-1").contains("错误的原值");
                });
        // 原记录不存在于包内 422
        assertApiStatus(() -> sealService.appendAppendix("INC-S10", "alice",
                new AppendixRequest(key(), 1, "ACT-404", "note", "a", "b", "r")),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 字段不存在于固化快照 422
        assertApiStatus(() -> sealService.appendAppendix("INC-S10", "alice",
                new AppendixRequest(key(), 1, "ACT-1", "noSuchField", "a", "b", "r")),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 封存包不存在 404
        assertApiStatus(() -> sealService.appendAppendix("INC-S10", "alice",
                new AppendixRequest(key(), 9, "ACT-1", "note", "a", "b", "r")),
                HttpStatus.NOT_FOUND);
        // 事件不存在 404
        assertApiStatus(() -> sealService.appendAppendix("INC-S10-X", "alice",
                new AppendixRequest(key(), 1, "ACT-1", "note", "a", "b", "r")),
                HttpStatus.NOT_FOUND);
        // 附录追加后总数不变（上述失败均不占位）
        assertThat(sealService.listAppendices("INC-S10").appendices()).hasSize(2);
    }

    @Test
    void appendix_commandKeyReplayAndConflict() {
        commanding("INC-S11", "alice");
        addAction("INC-S11", "alice", "ACT-1", T0.plusSeconds(10));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
        sealService.createSeal("INC-S11", "alice", new SealCreateRequest(key(), T0.plusSeconds(100)));

        String commandKey = key();
        AppendixView first = sealService.appendAppendix("INC-S11", "alice",
                new AppendixRequest(commandKey, 1, "ACT-1", "note", "记录-ACT-1", "新值", "理由"));
        AppendixView replay = sealService.appendAppendix("INC-S11", "alice",
                new AppendixRequest(commandKey, 1, "ACT-1", "note", "记录-ACT-1", "新值", "理由"));
        assertThat(replay).isEqualTo(first);
        assertThat(sealService.listAppendices("INC-S11").appendices()).hasSize(1);
        // 同键异参 409
        assertApiStatus(() -> sealService.appendAppendix("INC-S11", "alice",
                new AppendixRequest(commandKey, 1, "ACT-1", "note", "记录-ACT-1", "别的值", "理由")),
                HttpStatus.CONFLICT);
    }

    @Test
    void queries_readOnlyAndDiagnosticsAccurate() {
        commanding("INC-S12", "alice");
        addAction("INC-S12", "alice", "ACT-1", T0.plusSeconds(10));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(1000));
        sealService.createSeal("INC-S12", "alice", new SealCreateRequest(key(), T0.plusSeconds(100)));
        ((ControllableClock) clock).setInstant(T0.plusSeconds(2000));
        sealService.createSeal("INC-S12", "alice", new SealCreateRequest(key(), T0.plusSeconds(2000)));
        sealService.appendAppendix("INC-S12", "alice",
                new AppendixRequest(key(), 1, "ACT-1", "note", "记录-ACT-1", "新值", "理由"));

        SealDiagnosticsView diagnostics = sealService.diagnostics("INC-S12");
        assertThat(diagnostics.sealCount()).isEqualTo(2);
        assertThat(diagnostics.latestVersion()).isEqualTo(2);
        assertThat(diagnostics.earliestSealUntil()).isEqualTo(T0.plusSeconds(100));
        assertThat(diagnostics.latestSealUntil()).isEqualTo(T0.plusSeconds(2000));
        assertThat(diagnostics.recordCount()).isEqualTo(2);
        assertThat(diagnostics.edgeCount()).isZero();
        assertThat(diagnostics.appendixCount()).isEqualTo(1);

        // 只读查询不改变状态：重复查询结果一致，且不产生新的幂等键或封存包
        sealService.listSeals("INC-S12");
        sealService.getSeal("INC-S12", 1);
        sealService.listAppendices("INC-S12");
        sealService.diagnostics("INC-S12");
        Integer keyCount = jdbc.queryForObject("SELECT COUNT(*) FROM command_keys", Integer.class);
        Integer sealCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_log_seals", Integer.class);
        assertThat(sealCount).isEqualTo(2);
        assertThat(sealService.diagnostics("INC-S12")).isEqualTo(diagnostics);
        // 写操作产生的幂等键：2 次封存 + 1 次附录（指令/接管等另有若干），查询不新增
        Integer keyCountAfter = jdbc.queryForObject("SELECT COUNT(*) FROM command_keys",
                Integer.class);
        assertThat(keyCountAfter).isEqualTo(keyCount);
        // 无封存包事件的诊断：空值语义稳定
        commanding("INC-S12-EMPTY", "bob");
        SealDiagnosticsView empty = sealService.diagnostics("INC-S12-EMPTY");
        assertThat(empty.sealCount()).isZero();
        assertThat(empty.latestVersion()).isNull();
        assertThat(empty.earliestSealUntil()).isNull();
        assertThat(empty.latestSealUntil()).isNull();
    }
}
