package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.PresenceCheckInRequest;
import com.example.starter.incident.dto.Requests.PresenceRevokeRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.SupervisionEnableRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCompleteRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.PresenceCredentialView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 双人监护限时在场凭证服务测试：覆盖启用监护、签到代次推进、60 秒左闭右开有效期、
 * 完成双人校验（缺失/过期/旧代 422）、撤销与 403 越权、终态禁签到及 commandKey 幂等语义。
 * 时间全部由注入的可控 Clock 控制。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class PresenceServiceTest {

    /** 合成场景基准时刻：10:00:00Z。 */
    private static final Instant T0 = Instant.parse("2026-10-07T10:00:00Z");

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private java.time.Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM task_presence_credentials");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void at(Instant instant) {
        ((ControllableClock) clock).setInstant(instant);
    }

    private IncidentView commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "高危处置", "reporter-1"));
        return service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String actor, String taskKey) {
        service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "OPS", "高危处置", List.of()));
    }

    /** 创建 OPEN 任务并启用双人监护（执行人 op-exec、监护人 op-guard），版本变为 2。 */
    private void supervisedTask(String incidentKey, String taskKey) {
        commanding(incidentKey, "alice");
        createTask(incidentKey, "alice", taskKey);
        service.enableSupervision(incidentKey, taskKey, "alice",
                new SupervisionEnableRequest(key(), "op-exec", "op-guard", 1));
    }

    private PresenceCredentialView checkIn(String incidentKey, String taskKey, String actor) {
        return service.checkInPresence(incidentKey, taskKey, actor,
                new PresenceCheckInRequest(key(), 2));
    }

    private TaskView complete(String incidentKey, String taskKey,
                              Integer execGen, Integer guardGen) {
        return service.completeTask(incidentKey, taskKey, "alice",
                new TaskCompleteRequest(key(), execGen, guardGen, 2));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void enableSupervision_mainFlowAndValidation() {
        commanding("INC-S1", "alice");
        createTask("INC-S1", "alice", "T-1");
        // 执行人与监护人相同 → 400
        assertApiStatus(() -> service.enableSupervision("INC-S1", "T-1", "alice",
                new SupervisionEnableRequest(key(), "op-exec", "op-exec", 1)),
                HttpStatus.BAD_REQUEST);
        // 期望版本不符 → 409
        assertApiStatus(() -> service.enableSupervision("INC-S1", "T-1", "alice",
                new SupervisionEnableRequest(key(), "op-exec", "op-guard", 9)),
                HttpStatus.CONFLICT);
        // 非当前指挥人 → 409
        assertApiStatus(() -> service.enableSupervision("INC-S1", "T-1", "bob",
                new SupervisionEnableRequest(key(), "op-exec", "op-guard", 1)),
                HttpStatus.CONFLICT);
        // 正常启用：版本 1 → 2，固定双方
        TaskView enabled = service.enableSupervision("INC-S1", "T-1", "alice",
                new SupervisionEnableRequest(key(), "op-exec", "op-guard", 1));
        assertThat(enabled.version()).isEqualTo(2);
        assertThat(enabled.supervisionExecutor()).isEqualTo("op-exec");
        assertThat(enabled.supervisionGuardian()).isEqualTo("op-guard");
        // 重复启用 → 409
        assertApiStatus(() -> service.enableSupervision("INC-S1", "T-1", "alice",
                new SupervisionEnableRequest(key(), "op-exec", "op-guard", 2)),
                HttpStatus.CONFLICT);
        // 终态任务不能启用
        service.cancelTask("INC-S1", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> service.enableSupervision("INC-S1", "T-1", "alice",
                new SupervisionEnableRequest(key(), "a", "b", null)), HttpStatus.CONFLICT);
    }

    @Test
    void checkIn_generationAndValidityWindow() {
        supervisedTask("INC-S2", "T-1");
        // 10:00:00 执行人签到：代次 1，[10:00:00, 10:01:00) 左闭右开
        PresenceCredentialView first = checkIn("INC-S2", "T-1", "op-exec");
        assertThat(first.role()).isEqualTo("EXECUTOR");
        assertThat(first.operator()).isEqualTo("op-exec");
        assertThat(first.generation()).isEqualTo(1);
        assertThat(first.issuedAt()).isEqualTo(T0);
        assertThat(first.expiresAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(first.revokedAt()).isNull();
        // 10:00:10 监护人签到：各自独立代次
        at(T0.plusSeconds(10));
        PresenceCredentialView guardian = checkIn("INC-S2", "T-1", "op-guard");
        assertThat(guardian.role()).isEqualTo("GUARDIAN");
        assertThat(guardian.generation()).isEqualTo(1);
        assertThat(guardian.issuedAt()).isEqualTo(T0.plusSeconds(10));
        // 10:00:20 执行人重新签到：代次推进为 2，前代失效
        at(T0.plusSeconds(20));
        PresenceCredentialView second = checkIn("INC-S2", "T-1", "op-exec");
        assertThat(second.generation()).isEqualTo(2);
        assertThat(second.issuedAt()).isEqualTo(T0.plusSeconds(20));
        // 非固定操作者签到 → 403；未启用监护的任务 → 409
        assertApiStatus(() -> checkIn("INC-S2", "T-1", "mallory"), HttpStatus.FORBIDDEN);
        assertApiStatus(() -> checkIn("INC-S2", "T-1", "alice"), HttpStatus.FORBIDDEN);
        createTask("INC-S2", "alice", "T-2");
        assertApiStatus(() -> service.checkInPresence("INC-S2", "T-2", "op-exec",
                new PresenceCheckInRequest(key(), null)), HttpStatus.CONFLICT);
        // 期望版本不符 → 409
        assertApiStatus(() -> service.checkInPresence("INC-S2", "T-1", "op-exec",
                new PresenceCheckInRequest(key(), 7)), HttpStatus.CONFLICT);
    }

    @Test
    void completeSupervised_mainFlowFreezesCredentials() {
        supervisedTask("INC-S3", "T-1");
        // 合成两人 10:00:00、10:00:10 签到
        checkIn("INC-S3", "T-1", "op-exec");
        at(T0.plusSeconds(10));
        checkIn("INC-S3", "T-1", "op-guard");
        // 10:00:59 双方凭证均有效，可完成并冻结双方签发、到期时刻
        at(T0.plusSeconds(59));
        TaskView done = complete("INC-S3", "T-1", 1, 1);
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.doneBy()).isEqualTo("alice");
        assertThat(done.doneExecutorIssuedAt()).isEqualTo(T0);
        assertThat(done.doneExecutorExpiresAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(done.doneGuardianIssuedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(done.doneGuardianExpiresAt()).isEqualTo(T0.plusSeconds(70));
        // DONE 后禁止新签到 → 422
        assertApiStatus(() -> checkIn("INC-S3", "T-1", "op-exec"),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void completeSupervised_expiredThenReCheckInRequiresNewGeneration() {
        supervisedTask("INC-S4", "T-1");
        checkIn("INC-S4", "T-1", "op-exec");
        at(T0.plusSeconds(10));
        checkIn("INC-S4", "T-1", "op-guard");
        // 10:01:00 第一人（10:00:00 签发）已到期（左闭右开），完成 422 且任务不变
        at(T0.plusSeconds(60));
        assertApiStatus(() -> complete("INC-S4", "T-1", 1, 1),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.getTask("INC-S4", "T-1").status()).isEqualTo("OPEN");
        // 重新签到推进代次；引用旧代次仍 422
        PresenceCredentialView renewed = checkIn("INC-S4", "T-1", "op-exec");
        assertThat(renewed.generation()).isEqualTo(2);
        assertApiStatus(() -> complete("INC-S4", "T-1", 1, 1),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 引用新代次后完成
        TaskView done = complete("INC-S4", "T-1", 2, 1);
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.doneExecutorIssuedAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void completeSupervised_missingOrStaleGenerations_422() {
        supervisedTask("INC-S5", "T-1");
        checkIn("INC-S5", "T-1", "op-exec");
        checkIn("INC-S5", "T-1", "op-guard");
        // 缺失代次 → 422
        assertApiStatus(() -> complete("INC-S5", "T-1", null, 1),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> complete("INC-S5", "T-1", 1, null),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 不存在的代次 → 422
        assertApiStatus(() -> complete("INC-S5", "T-1", 9, 1),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 旧代次（重新签到后引用前代）→ 422
        checkIn("INC-S5", "T-1", "op-guard");
        assertApiStatus(() -> complete("INC-S5", "T-1", 1, 1),
                HttpStatus.UNPROCESSABLE_ENTITY);
        // 全部 422 均不改变任务
        TaskView task = service.getTask("INC-S5", "T-1");
        assertThat(task.status()).isEqualTo("OPEN");
        assertThat(task.doneAt()).isNull();
        // 双方当前代次完成成功
        assertThat(complete("INC-S5", "T-1", 1, 2).status()).isEqualTo("DONE");
    }

    @Test
    void completeSupervised_expectedVersionAndBlockersStillApply() {
        commanding("INC-S6", "alice");
        createTask("INC-S6", "alice", "T-1");
        service.enableSupervision("INC-S6", "T-1", "alice",
                new SupervisionEnableRequest(key(), "op-exec", "op-guard", 1));
        checkIn("INC-S6", "T-1", "op-exec");
        checkIn("INC-S6", "T-1", "op-guard");
        // 期望版本不符 → 409
        assertApiStatus(() -> service.completeTask("INC-S6", "T-1", "alice",
                new TaskCompleteRequest(key(), 1, 1, 5)), HttpStatus.CONFLICT);
        // 期望版本匹配（启用后版本为 2）可完成
        assertThat(service.completeTask("INC-S6", "T-1", "alice",
                new TaskCompleteRequest(key(), 1, 1, 2)).status()).isEqualTo("DONE");
        // 原阻塞门禁仍生效：双人凭证有效但阻塞事件未解除 → 409
        commanding("INC-S6B", "bob");
        commanding("INC-S6C", "carol");
        createTask("INC-S6C", "carol", "T-2");
        service.enableSupervision("INC-S6C", "T-2", "carol",
                new SupervisionEnableRequest(key(), "op-exec", "op-guard", 1));
        checkIn("INC-S6C", "T-2", "op-exec");
        checkIn("INC-S6C", "T-2", "op-guard");
        assertApiStatus(() -> complete("INC-S6C", "T-2", 1, 1), HttpStatus.CONFLICT);
    }

    @Test
    void revoke_onlyCommanderAndInvalidatesCredential() {
        supervisedTask("INC-S7", "T-1");
        checkIn("INC-S7", "T-1", "op-exec");
        checkIn("INC-S7", "T-1", "op-guard");
        // 越权撤销 → 403
        assertApiStatus(() -> service.revokePresence("INC-S7", "T-1", "mallory",
                new PresenceRevokeRequest(key(), "op-exec", null)), HttpStatus.FORBIDDEN);
        // 当前指挥人撤销执行人凭证
        PresenceCredentialView revoked = service.revokePresence("INC-S7", "T-1", "alice",
                new PresenceRevokeRequest(key(), "op-exec", 2));
        assertThat(revoked.revokedAt()).isEqualTo(T0);
        // 撤销后完成 → 422，任务不变
        assertApiStatus(() -> complete("INC-S7", "T-1", 1, 1),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(service.getTask("INC-S7", "T-1").status()).isEqualTo("OPEN");
        // 重复撤销当前代次 → 409；撤销非固定操作者 → 403
        assertApiStatus(() -> service.revokePresence("INC-S7", "T-1", "alice",
                new PresenceRevokeRequest(key(), "op-exec", null)), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.revokePresence("INC-S7", "T-1", "alice",
                new PresenceRevokeRequest(key(), "mallory", null)), HttpStatus.FORBIDDEN);
        // 重新签到得到新代次后可完成
        checkIn("INC-S7", "T-1", "op-exec");
        assertThat(complete("INC-S7", "T-1", 2, 1).status()).isEqualTo("DONE");
    }

    @Test
    void cancelAndTerminalStates_blockCheckInAndCompletion() {
        supervisedTask("INC-S8", "T-1");
        checkIn("INC-S8", "T-1", "op-exec");
        checkIn("INC-S8", "T-1", "op-guard");
        // 取消任务后：签到 422、完成 409（终态）、撤销 422
        service.cancelTask("INC-S8", "T-1", "alice", new TaskActionRequest(key()));
        assertApiStatus(() -> checkIn("INC-S8", "T-1", "op-exec"),
                HttpStatus.UNPROCESSABLE_ENTITY);
        assertApiStatus(() -> complete("INC-S8", "T-1", 1, 1), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.revokePresence("INC-S8", "T-1", "alice",
                new PresenceRevokeRequest(key(), "op-exec", null)),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void commandKeyReplay_doesNotExtendValidityAndReturnsFirstEvidence() {
        supervisedTask("INC-S9", "T-1");
        // 同键签到重放：返回首次凭证，不延长有效期
        String checkInKey = key();
        PresenceCredentialView first = service.checkInPresence("INC-S9", "T-1", "op-exec",
                new PresenceCheckInRequest(checkInKey, 2));
        at(T0.plusSeconds(30));
        PresenceCredentialView replay = service.checkInPresence("INC-S9", "T-1", "op-exec",
                new PresenceCheckInRequest(checkInKey, 2));
        assertThat(replay).isEqualTo(first);
        assertThat(replay.issuedAt()).isEqualTo(T0);
        assertThat(replay.generation()).isEqualTo(1);
        // 同键改参 → 409
        assertApiStatus(() -> service.checkInPresence("INC-S9", "T-1", "op-exec",
                new PresenceCheckInRequest(checkInKey, 9)), HttpStatus.CONFLICT);
        // 旧成功完成只返回首次证据：同键重放冻结时刻不变
        checkIn("INC-S9", "T-1", "op-guard");
        String completeKey = key();
        at(T0.plusSeconds(40));
        TaskView done = service.completeTask("INC-S9", "T-1", "alice",
                new TaskCompleteRequest(completeKey, 1, 1, 2));
        at(T0.plusSeconds(50));
        TaskView doneReplay = service.completeTask("INC-S9", "T-1", "alice",
                new TaskCompleteRequest(completeKey, 1, 1, 2));
        assertThat(doneReplay).isEqualTo(done);
        assertThat(doneReplay.doneAt()).isEqualTo(T0.plusSeconds(40));
        // 权限先于重放：非固定操作者持同键 → 403 而非重放成功
        assertApiStatus(() -> service.checkInPresence("INC-S9", "T-1", "mallory",
                new PresenceCheckInRequest(checkInKey, 2)), HttpStatus.FORBIDDEN);
    }
}
