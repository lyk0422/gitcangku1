package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.EscalationAckRequest;
import com.example.starter.incident.dto.Requests.EscalationCheckRequest;
import com.example.starter.incident.dto.Requests.HandoverAcceptRequest;
import com.example.starter.incident.dto.Requests.HandoverInitiateRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.HandoverDetailView;
import com.example.starter.incident.dto.Responses.HandoverHistoryView;
import com.example.starter.incident.dto.Responses.HandoverSummary;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 联合指挥交接服务测试：覆盖闭包计算、发起校验（400/403/404/409/422）、
 * 接受版本一致性、整体回滚、终态拒绝、查询只读与 commandKey 幂等语义。
 * 使用可控 Clock，时间相关断言不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class HandoverServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");

    @Autowired
    private HandoverService handovers;

    @Autowired
    private IncidentService incidents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_handover_incidents");
        jdbc.update("DELETE FROM incident_handovers");
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

    private void commanding(String incidentKey, String commander) {
        incidents.report(new ReportRequest(incidentKey, "S2", "故障 " + incidentKey, "reporter-1"));
        incidents.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String actor, String taskKey,
                            List<String> blockers) {
        incidents.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G1", "任务 " + taskKey, blockers));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    private HandoverDetailView initiate(String handoverKey, String actor, String to,
                                        List<String> incidentKeys) {
        return handovers.initiate(actor,
                new HandoverInitiateRequest(key(), handoverKey, to, incidentKeys));
    }

    @Test
    void mainFlow_transitiveClosure_acceptSwitchesAllAtomically() {
        // 依赖链：A 的 OPEN 任务被 B 阻塞，B 的 OPEN 任务被 C 阻塞
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        commanding("INC-C", "alice");
        commanding("INC-X", "alice");
        createTask("INC-A", "alice", "T-A", List.of("INC-B"));
        createTask("INC-B", "alice", "T-B", List.of("INC-C"));

        // 提交 {INC-A, INC-X}：遗漏闭包事件，422 并列出缺失
        assertThatThrownBy(() -> initiate("HO-1", "alice", "bob", List.of("INC-A", "INC-X")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("CLOSURE_INCOMPLETE");
                    assertThat(e.details()).isEqualTo(List.of("INC-B", "INC-C"));
                });

        // 恰好覆盖闭包：发起成功，预览冻结摘要与版本
        HandoverDetailView initiated = initiate("HO-1", "alice", "bob",
                List.of("INC-C", "INC-A", "INC-B"));
        assertThat(initiated.handover().status()).isEqualTo("PENDING");
        assertThat(initiated.handover().incidentKeys())
                .containsExactly("INC-A", "INC-B", "INC-C");
        assertThat(initiated.handoverVersion()).hasSize(64);
        HandoverSummary summary = initiated.summary();
        assertThat(summary.incidents()).hasSize(3);
        var incA = summary.incidents().get(0);
        assertThat(incA.incidentKey()).isEqualTo("INC-A");
        assertThat(incA.commander()).isEqualTo("alice");
        assertThat(incA.status()).isEqualTo("COMMANDING");
        assertThat(incA.openTasks()).hasSize(1);
        assertThat(incA.openTasks().get(0).taskKey()).isEqualTo("T-A");
        assertThat(incA.openTasks().get(0).version()).isEqualTo(1);
        assertThat(incA.openTasks().get(0).dependencies()).containsExactly("INC-B");

        // 接受：同一事务内切换全部事件指挥人，保存不可变快照
        HandoverDetailView accepted = handovers.accept("HO-1", "bob",
                new HandoverAcceptRequest(key(), initiated.handoverVersion(), summary));
        assertThat(accepted.handover().status()).isEqualTo("ACCEPTED");
        assertThat(accepted.handover().acceptedAt()).isNotNull();
        assertThat(incidents.get("INC-A").commander()).isEqualTo("bob");
        assertThat(incidents.get("INC-B").commander()).isEqualTo("bob");
        assertThat(incidents.get("INC-C").commander()).isEqualTo("bob");

        // 快照不可变：接受后再变更任务状态，详情仍返回接受时快照
        incidents.cancelTask("INC-A", "T-A", "bob", new TaskActionRequest(key()));
        HandoverDetailView detail = handovers.getHandover("HO-1");
        assertThat(detail.handoverVersion()).isEqualTo(accepted.handoverVersion());
        assertThat(detail.summary()).isEqualTo(summary);

        // 历史查询：三个事件均能查到该交接单
        for (String ik : List.of("INC-A", "INC-B", "INC-C")) {
            HandoverHistoryView history = handovers.historyForIncident(ik);
            assertThat(history.handovers()).hasSize(1);
            assertThat(history.handovers().get(0).handoverKey()).isEqualTo("HO-1");
            assertThat(history.handovers().get(0).status()).isEqualTo("ACCEPTED");
        }
    }

    @Test
    void initiate_validationErrors() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        // 重复键 → 400
        assertApiStatus(() -> initiate("HO-D", "alice", "bob", List.of("INC-A", "INC-A")),
                HttpStatus.BAD_REQUEST);
        // 数量不足 → 400
        assertApiStatus(() -> initiate("HO-D", "alice", "bob", List.of("INC-A")),
                HttpStatus.BAD_REQUEST);
        // 数量超限 → 400
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add("INC-X" + i);
        }
        assertApiStatus(() -> initiate("HO-D", "alice", "bob", tooMany),
                HttpStatus.BAD_REQUEST);
        // 接收人与当前指挥人相同 → 400
        assertApiStatus(() -> initiate("HO-D", "alice", "alice", List.of("INC-A", "INC-B")),
                HttpStatus.BAD_REQUEST);
        // 事件不存在 → 404
        assertApiStatus(() -> initiate("HO-D", "alice", "bob", List.of("INC-A", "INC-NONE")),
                HttpStatus.NOT_FOUND);
        // 终态事件属于多余 → 400
        commanding("INC-R", "alice");
        incidents.changeStatus("INC-R", "alice", new StatusRequest(key(), "CONTAINED"));
        incidents.changeStatus("INC-R", "alice", new StatusRequest(key(), "RESOLVED"));
        assertApiStatus(() -> initiate("HO-D", "alice", "bob", List.of("INC-A", "INC-R")),
                HttpStatus.BAD_REQUEST);
    }

    @Test
    void initiate_foreignCommander_returns403() {
        commanding("INC-A", "alice");
        commanding("INC-X", "alice");
        commanding("INC-B", "carol");
        // 混入非本人指挥事件 → 403
        assertApiStatus(() -> initiate("HO-F", "alice", "bob", List.of("INC-A", "INC-B")),
                HttpStatus.FORBIDDEN);
        // 闭包包含他人事件：只提交本人事件 → 422 缺失；混入 → 403
        createTask("INC-A", "alice", "T-A", List.of("INC-B"));
        assertThatThrownBy(() -> initiate("HO-F", "alice", "bob", List.of("INC-A", "INC-X")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.details()).isEqualTo(List.of("INC-B"));
                });
        assertApiStatus(() -> initiate("HO-F", "alice", "bob", List.of("INC-A", "INC-B")),
                HttpStatus.FORBIDDEN);
    }

    @Test
    void initiate_duplicateHandoverKey_returns409() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        initiate("HO-DUP", "alice", "bob", List.of("INC-A", "INC-B"));
        assertApiStatus(() -> initiate("HO-DUP", "alice", "carol", List.of("INC-A", "INC-B")),
                HttpStatus.CONFLICT);
    }

    @Test
    void initiate_idempotency_reorderReplays_failureDoesNotConsumeKey() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        commanding("INC-C", "alice");
        createTask("INC-A", "alice", "T-A", List.of("INC-C"));

        // 失败不占键：先因遗漏 422，随后同键换参（补全集）可成功
        String commandKey = key();
        assertApiStatus(() -> handovers.initiate("alice",
                        new HandoverInitiateRequest(commandKey, "HO-I", "bob",
                                List.of("INC-A", "INC-B"))),
                HttpStatus.UNPROCESSABLE_ENTITY);
        HandoverDetailView initiated = handovers.initiate("alice",
                new HandoverInitiateRequest(commandKey, "HO-I", "bob",
                        List.of("INC-A", "INC-B", "INC-C")));
        assertThat(initiated.handover().handoverKey()).isEqualTo("HO-I");

        // 同键同参（集合换序）重放首次响应
        HandoverDetailView replayed = handovers.initiate("alice",
                new HandoverInitiateRequest(commandKey, "HO-I", "bob",
                        List.of("INC-C", "INC-A", "INC-B")));
        assertThat(replayed).isEqualTo(initiated);
        // 同键异参 → 409
        assertApiStatus(() -> handovers.initiate("alice",
                        new HandoverInitiateRequest(commandKey, "HO-I", "carol",
                                List.of("INC-A", "INC-B", "INC-C"))),
                HttpStatus.CONFLICT);
        assertApiStatus(() -> handovers.initiate("alice",
                        new HandoverInitiateRequest(commandKey, "HO-OTHER", "bob",
                                List.of("INC-A", "INC-B", "INC-C"))),
                HttpStatus.CONFLICT);
    }

    @Test
    void accept_changedTask_returns409_andNoPartialTakeover() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        createTask("INC-A", "alice", "T-A", List.of());
        HandoverDetailView initiated = initiate("HO-T", "alice", "bob",
                List.of("INC-A", "INC-B"));

        // 预览后任务完成 → 版本变化 → 409
        incidents.completeTask("INC-A", "T-A", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> handovers.accept("HO-T", "bob",
                        new HandoverAcceptRequest(key(), initiated.handoverVersion(),
                                initiated.summary())),
                HttpStatus.CONFLICT);
        // 不允许部分接管：指挥人不变，交接单仍 PENDING
        assertThat(incidents.get("INC-A").commander()).isEqualTo("alice");
        assertThat(incidents.get("INC-B").commander()).isEqualTo("alice");
        assertThat(handovers.getHandover("HO-T").handover().status()).isEqualTo("PENDING");
    }

    @Test
    void accept_newTaskOrCommanderChange_returns409() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        HandoverDetailView initiated = initiate("HO-N", "alice", "bob",
                List.of("INC-A", "INC-B"));

        // 预览后新建 OPEN 任务 → 409
        createTask("INC-A", "alice", "T-NEW", List.of());
        assertApiStatus(() -> handovers.accept("HO-N", "bob",
                        new HandoverAcceptRequest(key(), initiated.handoverVersion(),
                                initiated.summary())),
                HttpStatus.CONFLICT);

        // 单事件交接切换指挥人 → 409
        commanding("INC-C", "alice");
        commanding("INC-D", "alice");
        HandoverDetailView initiated2 = initiate("HO-N2", "alice", "bob",
                List.of("INC-C", "INC-D"));
        incidents.initiateTransfer("INC-C", "alice", new TransferRequest(key(), "carol"));
        incidents.acceptTransfer("INC-C", "carol", new TransferAcceptRequest(key()));
        assertApiStatus(() -> handovers.accept("HO-N2", "bob",
                        new HandoverAcceptRequest(key(), initiated2.handoverVersion(),
                                initiated2.summary())),
                HttpStatus.CONFLICT);
    }

    @Test
    void accept_escalationChange_returns409() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        // 制造 OPEN 升级记录（S2 期限 15 分钟）
        ((ControllableClock) clock).setInstant(T0.plusSeconds(16 * 60));
        incidents.checkEscalation("INC-A", new EscalationCheckRequest(key()));

        HandoverDetailView initiated = initiate("HO-E", "alice", "bob",
                List.of("INC-A", "INC-B"));
        var incA = initiated.summary().incidents().get(0);
        assertThat(incA.unacknowledgedEscalations()).hasSize(1);
        assertThat(incA.unacknowledgedEscalations().get(0).version()).isEqualTo(1);

        // 确认升级 → 未确认升级集合变化 → 409
        incidents.acknowledgeEscalation("INC-A", "alice",
                new EscalationAckRequest(key(), "已处理"));
        assertApiStatus(() -> handovers.accept("HO-E", "bob",
                        new HandoverAcceptRequest(key(), initiated.handoverVersion(),
                                initiated.summary())),
                HttpStatus.CONFLICT);
    }

    @Test
    void accept_wrongReceiverAndTerminalState_rejected() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        HandoverDetailView initiated = initiate("HO-W", "alice", "bob",
                List.of("INC-A", "INC-B"));

        // 非指定接收人 → 403
        assertApiStatus(() -> handovers.accept("HO-W", "carol",
                        new HandoverAcceptRequest(key(), initiated.handoverVersion(),
                                initiated.summary())),
                HttpStatus.FORBIDDEN);
        // 摘要与版本不匹配 → 400
        assertApiStatus(() -> handovers.accept("HO-W", "bob",
                        new HandoverAcceptRequest(key(), "0".repeat(64), initiated.summary())),
                HttpStatus.BAD_REQUEST);
        // 不存在的交接单 → 404
        assertApiStatus(() -> handovers.accept("HO-NONE", "bob",
                        new HandoverAcceptRequest(key(), initiated.handoverVersion(),
                                initiated.summary())),
                HttpStatus.NOT_FOUND);

        // 接受成功后重复接受 → 409
        handovers.accept("HO-W", "bob",
                new HandoverAcceptRequest(key(), initiated.handoverVersion(),
                        initiated.summary()));
        assertApiStatus(() -> handovers.accept("HO-W", "bob",
                        new HandoverAcceptRequest(key(), initiated.handoverVersion(),
                                initiated.summary())),
                HttpStatus.CONFLICT);
    }

    @Test
    void accept_terminalIncident_rejectedEvenWithFreshVersion() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        HandoverDetailView initiated = initiate("HO-TT", "alice", "bob",
                List.of("INC-A", "INC-B"));

        // 发起后 INC-A 进入 RESOLVED 终态
        incidents.changeStatus("INC-A", "alice", new StatusRequest(key(), "CONTAINED"));
        incidents.changeStatus("INC-A", "alice", new StatusRequest(key(), "RESOLVED"));

        // 旧版本 → 409（版本不一致）
        assertApiStatus(() -> handovers.accept("HO-TT", "bob",
                        new HandoverAcceptRequest(key(), initiated.handoverVersion(),
                                initiated.summary())),
                HttpStatus.CONFLICT);
        // 实时预览拿到包含终态的新版本 → 仍 409（终态事件拒绝）
        HandoverDetailView fresh = handovers.getHandover("HO-TT");
        assertThat(fresh.summary().incidents().get(0).status()).isEqualTo("RESOLVED");
        assertApiStatus(() -> handovers.accept("HO-TT", "bob",
                        new HandoverAcceptRequest(key(), fresh.handoverVersion(), fresh.summary())),
                HttpStatus.CONFLICT);
        assertThat(incidents.get("INC-B").commander()).isEqualTo("alice");
    }

    @Test
    void accept_idempotency_sameKeyReplays() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        HandoverDetailView initiated = initiate("HO-AI", "alice", "bob",
                List.of("INC-A", "INC-B"));

        String commandKey = key();
        HandoverDetailView accepted = handovers.accept("HO-AI", "bob",
                new HandoverAcceptRequest(commandKey, initiated.handoverVersion(),
                        initiated.summary()));
        // 同键同参重放首次响应，不产生二次效果
        HandoverDetailView replayed = handovers.accept("HO-AI", "bob",
                new HandoverAcceptRequest(commandKey, initiated.handoverVersion(),
                        initiated.summary()));
        assertThat(replayed).isEqualTo(accepted);
        // 同键异参 → 409
        assertApiStatus(() -> handovers.accept("HO-AI", "bob",
                        new HandoverAcceptRequest(commandKey, "1".repeat(64),
                                initiated.summary())),
                HttpStatus.CONFLICT);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void queries_areReadOnly() {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        HandoverDetailView initiated = initiate("HO-Q", "alice", "bob",
                List.of("INC-A", "INC-B"));

        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM command_keys",
                Integer.class);
        handovers.getHandover("HO-Q");
        handovers.getHandover("HO-Q");
        handovers.historyForIncident("INC-A");
        handovers.historyForIncident("INC-B");
        // 查询不写数据：幂等键数量不变，交接单仍为 PENDING
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM command_keys",
                Integer.class);
        assertThat(after).isEqualTo(before);
        assertThat(handovers.getHandover("HO-Q").handover().status()).isEqualTo("PENDING");
        assertThat(handovers.getHandover("HO-Q").handoverVersion())
                .isEqualTo(initiated.handoverVersion());
        // 不存在的事件/交接单 → 404
        assertApiStatus(() -> handovers.getHandover("HO-NONE"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> handovers.historyForIncident("INC-NONE"), HttpStatus.NOT_FOUND);
    }
}
