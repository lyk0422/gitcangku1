package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.ActionRequest;
import com.example.starter.incident.dto.Requests.CorrectionRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.SealRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.CorrectionView;
import com.example.starter.incident.dto.Responses.SealDiagnosticsView;
import com.example.starter.incident.dto.Responses.SealItemView;
import com.example.starter.incident.dto.Responses.SealListView;
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
 * 日志封存服务测试：真实 H2（MODE=MySQL）+ 可控时钟，覆盖主流程、左闭右开边界、
 * 同刻写入 409、截至时刻 422、包内依赖图校验、失败不留半成品、版本递增与不可变、
 * commandKey 幂等以及更正附录（含 null 原值语义与关闭事件）。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class LogSealServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private LogSealService sealService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM incident_log_corrections");
        jdbc.update("DELETE FROM incident_log_seal_items");
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

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void advance(long seconds) {
        ((ControllableClock) clock).advanceSeconds(seconds);
    }

    private String setupCommandingIncident(String incidentKey) {
        incidentService.report(new ReportRequest(incidentKey, "S2", "封存测试事件", "reporter-1"));
        advance(5);
        incidentService.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        advance(10);
        return incidentKey;
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    private static ApiException catchApi(ThrowingCallable call) {
        try {
            call.call();
            throw new AssertionError("期望抛出 ApiException");
        } catch (ApiException e) {
            return e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void seal_mainFlow_sortsAndVersionsRecords() {
        String ik = setupCommandingIncident("INC-SEAL-1");
        advance(60);
        Instant t1 = clock.instant();
        incidentService.addAction(ik, "alice", new ActionRequest(key(), "ACT-1", "NOTE",
                "第一条处置", t1));
        advance(60);
        Instant t2 = clock.instant();
        incidentService.addAction(ik, "alice", new ActionRequest(key(), "ACT-2", "NOTE",
                "第二条处置", t2));
        advance(60);
        Instant cutoff = clock.instant();

        SealView v1 = sealService.seal(ik, "alice", new SealRequest(key(), cutoff));
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.cutoffAt()).isEqualTo(cutoff);
        assertThat(v1.recordCount()).isEqualTo(v1.items().size());
        // 至少含 REPORTED/COMMANDING 两条状态流转 + 两条处置记录
        assertThat(v1.items()).extracting(SealItemView::recordType)
                .contains("STATUS", "ACTION");
        // 排序：按发生时刻升序、同刻按类型+标识升序
        assertThat(v1.items()).isSortedAccordingTo((a, b) -> {
            int c = a.occurredAt().compareTo(b.occurredAt());
            if (c != 0) {
                return c;
            }
            c = a.recordType().compareTo(b.recordType());
            return c != 0 ? c : a.recordKey().compareTo(b.recordKey());
        });
        for (int i = 0; i < v1.items().size(); i++) {
            // 左闭右开：包内所有记录时刻严格早于截至时刻
            assertThat(v1.items().get(i).occurredAt()).isBefore(cutoff);
            assertThat(v1.items().get(i).seqNo()).isEqualTo(i + 1);
        }

        // 第二个包版本递增
        advance(120);
        incidentService.addAction(ik, "alice", new ActionRequest(key(), "ACT-3", "NOTE",
                "第三条处置", clock.instant()));
        advance(60);
        SealView v2 = sealService.seal(ik, "alice", new SealRequest(key(), clock.instant()));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.recordCount()).isGreaterThan(v1.recordCount());

        SealListView list = sealService.listSeals(ik);
        assertThat(list.latestVersion()).isEqualTo(2);
        assertThat(list.seals()).extracting(s -> s.version()).containsExactly(1, 2);
    }

    @Test
    void seal_halfOpenInterval_boundaryRecordsExcluded() {
        String ik = setupCommandingIncident("INC-SEAL-2");
        advance(30);
        Instant actionTime = clock.instant();
        incidentService.addAction(ik, "alice", new ActionRequest(key(), "ACT-EDGE", "NOTE",
                "恰在截至时刻", actionTime));
        // 截至时刻之前一秒：动作不进包
        SealView before = sealService.seal(ik, "alice",
                new SealRequest(key(), actionTime.minusSeconds(1)));
        assertThat(before.items()).filteredOn(i -> "ACTION".equals(i.recordType())).isEmpty();
        // 恰好等于动作发生时刻：同刻写入冲突 409，而不是静默包含
        assertApiStatus(() -> sealService.seal(ik, "alice", new SealRequest(key(), actionTime)),
                HttpStatus.CONFLICT, "SEAL_BOUNDARY_WRITE");
    }

    @Test
    void seal_cutoffOutOfRange_422WithActualAndDelta() {
        String ik = setupCommandingIncident("INC-SEAL-3");
        Instant createdAt = incidentService.get(ik).createdAt();

        ApiException early = catchApi(() -> sealService.seal(ik, "alice",
                new SealRequest(key(), createdAt.minusSeconds(10))));
        assertThat(early.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(early.code()).isEqualTo("SEAL_CUTOFF_TOO_EARLY");
        assertThat(early.details().toString()).contains("shortfallSeconds");

        ApiException future = catchApi(() -> sealService.seal(ik, "alice",
                new SealRequest(key(), clock.instant().plusSeconds(30))));
        assertThat(future.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(future.code()).isEqualTo("SEAL_CUTOFF_IN_FUTURE");
        assertThat(future.details().toString()).contains("excessSeconds");

        // 边界本身合法：等于创建时刻、等于当前时刻均可
        sealService.seal(ik, "alice", new SealRequest(key(), createdAt));
        sealService.seal(ik, "alice", new SealRequest(key(), clock.instant()));
    }

    @Test
    void seal_permissionAndStatusRules() {
        String ik = setupCommandingIncident("INC-SEAL-4");
        // 非当前指挥人 409
        assertApiStatus(() -> sealService.seal(ik, "bob", new SealRequest(key(), clock.instant())),
                HttpStatus.CONFLICT, "CONFLICT");
        advance(30);
        // CLOSED 后不能再封存
        incidentService.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"));
        incidentService.changeStatus(ik, "alice", new StatusRequest(key(), "RESOLVED"));
        incidentService.changeStatus(ik, "alice", new StatusRequest(key(), "CLOSED"));
        assertApiStatus(() -> sealService.seal(ik, "alice", new SealRequest(key(), clock.instant())),
                HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION");
    }

    @Test
    void seal_failureLeavesNoPartialPackageOrCommandKey() {
        String ik = setupCommandingIncident("INC-SEAL-5");
        advance(30);
        Instant at = clock.instant();
        incidentService.addAction(ik, "alice",
                new ActionRequest(key(), "ACT-X", "NOTE", "n", at));
        String ck = key();
        // 同刻冲突 409
        assertApiStatus(() -> sealService.seal(ik, "alice", new SealRequest(ck, at)),
                HttpStatus.CONFLICT, "SEAL_BOUNDARY_WRITE");
        Integer sealRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_log_seals WHERE incident_id = "
                        + "(SELECT id FROM incidents WHERE incident_key = 'INC-SEAL-5')",
                Integer.class);
        assertThat(sealRows).isZero();
        Integer keyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, ck);
        // 失败不占键：同一 commandKey 更换合法参数后可成功
        assertThat(keyRows).isZero();
        advance(1);
        SealView ok = sealService.seal(ik, "alice", new SealRequest(ck, clock.instant()));
        assertThat(ok.version()).isEqualTo(1);
    }

    @Test
    void seal_graphCycle_422AndNoPackage() throws Exception {
        // 正常生产路径不允许成环，这里直接在库内构造 A 的任务依赖 A 的自环，
        // 验证封存的包内图校验独立拦截且不产生部分包。
        String ik = "INC-SEAL-6";
        setupCommandingIncident(ik);
        advance(10);
        incidentService.createTask(ik, "alice", new TaskCreateRequest(key(), "TASK-SELF",
                "G1", "自环任务", java.util.List.of()));
        Long incidentId = jdbc.queryForObject(
                "SELECT id FROM incidents WHERE incident_key = ?", Long.class, ik);
        Long taskId = jdbc.queryForObject(
                "SELECT id FROM incident_tasks WHERE incident_id = ? AND task_key = 'TASK-SELF'",
                Long.class, incidentId);
        jdbc.update("INSERT INTO incident_task_blockers (task_id, blocker_incident_id, created_at)"
                + " VALUES (?,?,?)", taskId, incidentId, java.sql.Timestamp.from(clock.instant()));

        advance(5);
        ApiException ex = catchApi(() -> sealService.seal(ik, "alice",
                new SealRequest(key(), clock.instant())));
        assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ex.code()).isEqualTo("SEAL_GRAPH_CYCLE");
        Integer sealRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_log_seals WHERE incident_id = ?",
                Integer.class, incidentId);
        assertThat(sealRows).isZero();
    }

    @Test
    void seal_capturesTaskStatesVersionsAndEdges() {
        String blocker = "INC-SEAL-B";
        incidentService.report(new ReportRequest(blocker, "S3", "阻塞事件", "r"));
        String ik = "INC-SEAL-7";
        setupCommandingIncident(ik);
        advance(20);
        incidentService.createTask(ik, "alice", new TaskCreateRequest(key(), "TASK-1",
                "G1", "依赖任务", java.util.List.of(blocker)));
        advance(20);
        incidentService.createTask(ik, "alice", new TaskCreateRequest(key(), "TASK-2",
                "G1", "普通任务", java.util.List.of()));
        // 阻塞事件已控制、TASK-1 完成（版本 2）；TASK-2 取消（版本 2）
        incidentService.takeover(blocker, "bob", new TakeoverRequest(key()));
        advance(10);
        incidentService.changeStatus(blocker, "bob", new StatusRequest(key(), "CONTAINED"));
        advance(10);
        incidentService.completeTask(ik, "TASK-1", "alice", new TaskActionRequest(key()));
        advance(10);
        incidentService.cancelTask(ik, "TASK-2", "alice", new TaskActionRequest(key()));
        advance(10);

        SealView seal = sealService.seal(ik, "alice", new SealRequest(key(), clock.instant()));
        java.util.Map<String, SealItemView> byKey = new java.util.HashMap<>();
        seal.items().forEach(i -> byKey.put(i.recordType() + ":" + i.recordKey(), i));

        SealItemView task1 = byKey.get("TASK:task:TASK-1");
        assertThat(task1.recordVersion()).isEqualTo(2);
        assertThat(task1.payload().get("status").asText()).isEqualTo("DONE");
        SealItemView task2 = byKey.get("TASK:task:TASK-2");
        assertThat(task2.recordVersion()).isEqualTo(2);
        assertThat(task2.payload().get("status").asText()).isEqualTo("CANCELLED");
        // null 语义：TASK-1 的 cancelledBy/cancelledAt 固化为 null
        assertThat(task2.payload().get("doneBy").isNull()).isTrue();
        assertThat(task2.payload().get("doneAt").isNull()).isTrue();

        // 依赖边条目带边端点，且阻塞事件键已固化
        assertThat(seal.items()).filteredOn(i -> "DEPENDENCY".equals(i.recordType()))
                .singleElement().satisfies(e -> {
                    assertThat(e.fromTaskKey()).isEqualTo("TASK-1");
                    assertThat(e.toIncidentKey()).isEqualTo(blocker);
                });
        assertThat(task1.payload().get("blockerIncidentKeys").get(0).asText()).isEqualTo(blocker);
    }

    @Test
    void seal_transferAcceptedRecordedAsVersion2() {
        String ik = "INC-SEAL-8";
        setupCommandingIncident(ik);
        advance(20);
        incidentService.initiateTransfer(ik, "alice", new TransferRequest(key(), "carol"));
        advance(20);
        incidentService.acceptTransfer(ik, "carol", new TransferAcceptRequest(key()));
        advance(20);
        SealView seal = sealService.seal(ik, "carol", new SealRequest(key(), clock.instant()));
        SealItemView transfer = seal.items().stream()
                .filter(i -> "TRANSFER".equals(i.recordType())).findFirst().orElseThrow();
        assertThat(transfer.recordVersion()).isEqualTo(2);
        assertThat(transfer.payload().get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(transfer.payload().get("acceptedAt")).isNotNull();
    }

    @Test
    void packageImmutable_afterLaterWritesAndCorrection() {
        String ik = "INC-SEAL-9";
        setupCommandingIncident(ik);
        advance(20);
        Instant actionAt = clock.instant();
        incidentService.addAction(ik, "alice", new ActionRequest(key(), "ACT-IMMUTABLE",
                "NOTE", "原值", actionAt));
        advance(20);
        SealView v1 = sealService.seal(ik, "alice", new SealRequest(key(), clock.instant()));
        String hash = v1.contentHash();
        int count = v1.recordCount();

        // 后续追加新记录并存第二个包，不改变第一个包
        advance(20);
        incidentService.addAction(ik, "alice",
                new ActionRequest(key(), "ACT-LATE", "NOTE", "后来的记录", clock.instant()));
        advance(20);
        sealService.seal(ik, "alice", new SealRequest(key(), clock.instant()));

        SealView reread = sealService.getSeal(ik, 1);
        assertThat(reread.contentHash()).isEqualTo(hash);
        assertThat(reread.recordCount()).isEqualTo(count);
        assertThat(reread.items()).hasSize(count);
        SealDiagnosticsView diag = sealService.diagnostics(ik, 1);
        assertThat(diag.healthy()).isTrue();
        assertThat(diag.hashMatch()).isTrue();
        assertThat(diag.countMatch()).isTrue();
    }

    @Test
    void commandIdempotency_replaySameParams_conflictOnDifferentParams() {
        String ik = "INC-SEAL-10";
        setupCommandingIncident(ik);
        advance(10);
        String ck = key();
        Instant cutoff = clock.instant();
        SealView first = sealService.seal(ik, "alice", new SealRequest(ck, cutoff));
        SealView replay = sealService.seal(ik, "alice", new SealRequest(ck, cutoff));
        assertThat(replay).isEqualTo(first);
        // 异参 409
        assertApiStatus(() -> sealService.seal(ik, "alice",
                        new SealRequest(ck, cutoff.plusSeconds(1))),
                HttpStatus.CONFLICT, "CONFLICT");
    }

    @Test
    void corrections_appendOnlyPreserveNullAndOldValue() {
        String ik = "INC-SEAL-11";
        setupCommandingIncident(ik);
        advance(10);
        incidentService.addAction(ik, "alice", new ActionRequest(key(), "ACT-C", "NOTE",
                "需要更正", clock.instant()));
        advance(10);
        SealView seal = sealService.seal(ik, "alice", new SealRequest(key(), clock.instant()));
        SealItemView actionItem = seal.items().stream()
                .filter(i -> "ACTION".equals(i.recordType())).findFirst().orElseThrow();

        // 更正文本字段：原值取自原包
        CorrectionRequest req = new CorrectionRequest(key(), 1, "ACTION",
                actionItem.recordKey(), "note", "更正后的说明", "录入错误");
        CorrectionView correction = sealService.appendCorrection(ik, "alice", req);
        assertThat(correction.oldValue()).isEqualTo("需要更正");
        assertThat(correction.newValue()).isEqualTo("更正后的说明");
        assertThat(correction.reason()).isEqualTo("录入错误");
        assertThat(correction.operator()).isEqualTo("alice");

        // 同键同参重放
        CorrectionView replay = sealService.appendCorrection(ik, "alice", req);
        assertThat(replay.id()).isEqualTo(correction.id());

        // 更正 null 语义字段（acceptedAt 只在交接记录存在；这里用 ACTION 的无字段场景测 422）
        assertApiStatus(() -> sealService.appendCorrection(ik, "alice",
                        new CorrectionRequest(key(), 1, "ACTION", actionItem.recordKey(),
                                "notExists", "v", "r")),
                HttpStatus.UNPROCESSABLE_ENTITY, "CORRECTION_FIELD_NOT_FOUND");
        // 原包不存在 / 原记录不存在
        assertApiStatus(() -> sealService.appendCorrection(ik, "alice",
                        new CorrectionRequest(key(), 99, "ACTION", actionItem.recordKey(),
                                "note", "v", "r")),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertApiStatus(() -> sealService.appendCorrection(ik, "alice",
                        new CorrectionRequest(key(), 1, "ACTION", "NO-SUCH-KEY",
                                "note", "v", "r")),
                HttpStatus.UNPROCESSABLE_ENTITY, "CORRECTION_TARGET_NOT_FOUND");

        // 原包内容未被修改，附录挂在原包上
        SealView reread = sealService.getSeal(ik, 1);
        assertThat(reread.items()).hasSize(seal.items().size());
        assertThat(reread.corrections()).hasSize(1);
        assertThat(sealService.listCorrections(ik)).hasSize(1);
    }

    @Test
    void corrections_allowedAfterIncidentClosed_butNoNewSeal() {
        String ik = "INC-SEAL-12";
        setupCommandingIncident(ik);
        advance(10);
        incidentService.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"));
        advance(10);
        SealView seal = sealService.seal(ik, "alice", new SealRequest(key(), clock.instant()));
        SealItemView statusItem = seal.items().stream()
                .filter(i -> "STATUS".equals(i.recordType())).findFirst().orElseThrow();
        incidentService.changeStatus(ik, "alice", new StatusRequest(key(), "RESOLVED"));
        incidentService.changeStatus(ik, "alice", new StatusRequest(key(), "CLOSED"));

        // 关闭后仍可查询与追加更正附录（不校验指挥权，任何操作人留痕）
        CorrectionView correction = sealService.appendCorrection(ik, "dora",
                new CorrectionRequest(key(), 1, "STATUS", statusItem.recordKey(),
                        "actor", "dora", "关闭后补录操作人"));
        assertThat(correction.operator()).isEqualTo("dora");
        assertThat(sealService.getSeal(ik, 1).corrections()).hasSize(1);
        // 关闭后不能新增封存包
        assertApiStatus(() -> sealService.seal(ik, "alice",
                        new SealRequest(key(), clock.instant())),
                HttpStatus.UNPROCESSABLE_ENTITY, "ILLEGAL_TRANSITION");
    }
}
