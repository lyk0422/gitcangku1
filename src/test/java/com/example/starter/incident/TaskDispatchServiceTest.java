package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskDispatchRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Responses.TaskDispatchView;
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
 * 任务资源凭据派发与执行前检查服务测试。
 * 覆盖题干主流程（11:59 派发通过、12:00 执行前检查转 BLOCKED、换新凭据再派发）、
 * 派发与执行前的类型/区域/UTC 有效期/撤销拒绝分支、BLOCKED 不可完成与旧凭据不自动解锁、
 * requestId 重放/换资源 409、派发快照不可变与历史保留。时间全部由可控时钟驱动。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class TaskDispatchServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T11:00:00Z");
    private static final Instant AT_1159 = Instant.parse("2026-10-01T11:59:00Z");
    private static final Instant AT_1200 = Instant.parse("2026-10-01T12:00:00Z");
    private static final Instant EXPIRES_1200 = AT_1200;

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM task_dispatches");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "凭据场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String taskKey) {
        service.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), taskKey, "OPS", "执行操作", List.of()));
    }

    private void credential(String resource, String version, String type, String region,
                            Instant expiresAt) {
        service.registerCredential(new CredentialRegisterRequest(
                key(), resource, version, type, region, expiresAt));
    }

    private TaskDispatchRequest dispatchReq(String requestId, String resource, String version,
                                            String type, String region) {
        return new TaskDispatchRequest(requestId, resource, version, type, region);
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void fullFlow_dispatchCheckBeforeExpiry_startAndComplete() {
        commanding("INC-1", "alice");
        createTask("INC-1", "T-1");
        credential("R", "v1", "AWS_AK", "A", EXPIRES_1200);

        // 11:59 派发：同类型、覆盖 A、有效期未结束，通过
        ((ControllableClock) clock).setInstant(AT_1159);
        TaskView dispatched = service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-1", "R", "v1", "AWS_AK", "A"));
        assertThat(dispatched.status()).isEqualTo("DISPATCHED");
        assertThat(dispatched.credential().resourceKey()).isEqualTo("R");
        assertThat(dispatched.credential().credentialVersion()).isEqualTo("v1");
        assertThat(dispatched.credential().region()).isEqualTo("A");
        assertThat(dispatched.credential().expiresAt()).isEqualTo(EXPIRES_1200);
        assertThat(dispatched.credential().dispatchStatus()).isEqualTo("DISPATCHED");
        assertThat(dispatched.blockReason()).isNull();
        assertThat(dispatched.dispatches()).hasSize(1);
        assertThat(dispatched.dispatches().get(0).seq()).isEqualTo(1);
        assertThat(dispatched.dispatches().get(0).requestId()).isEqualTo("REQ-1");

        // 未完成派发不能直接完成
        assertApiStatus(() -> service.completeTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);

        // 11:59:30 执行前检查通过 → EXECUTING
        ((ControllableClock) clock).setInstant(Instant.parse("2026-10-01T11:59:30Z"));
        TaskView executing = service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(key()));
        assertThat(executing.status()).isEqualTo("EXECUTING");
        assertThat(executing.credential().dispatchStatus()).isEqualTo("EXECUTING");
        assertThat(executing.credential().startedAt())
                .isEqualTo(Instant.parse("2026-10-01T11:59:30Z"));

        // EXECUTING 不可取消
        assertApiStatus(() -> service.cancelTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);

        // 完成
        TaskView done = service.completeTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");
        assertThat(done.doneBy()).isEqualTo("alice");
    }

    @Test
    void expireAt1200_dispatchAt1159Passes_startAt1200Blocks_thenRedispatchNewCredential() {
        commanding("INC-1", "alice");
        createTask("INC-1", "T-1");
        credential("R", "v1", "AWS_AK", "A", EXPIRES_1200);

        ((ControllableClock) clock).setInstant(AT_1159);
        service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-1", "R", "v1", "AWS_AK", "A"));

        // 12:00 执行前检查：有效期已结束，任务转 BLOCKED（不抛错）
        ((ControllableClock) clock).setInstant(AT_1200);
        TaskView blocked = service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(key()));
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.blockReason()).isEqualTo("CREDENTIAL_EXPIRED");
        assertThat(blocked.blockedAt()).isEqualTo(AT_1200);
        assertThat(blocked.credential().credentialVersion()).isEqualTo("v1");
        assertThat(blocked.credential().dispatchStatus()).isEqualTo("BLOCKED");
        assertThat(blocked.dispatches()).hasSize(1);
        assertThat(blocked.dispatches().get(0).status()).isEqualTo("BLOCKED");
        assertThat(blocked.dispatches().get(0).blockReason()).isEqualTo("CREDENTIAL_EXPIRED");

        // BLOCKED 不能直接完成；旧凭据再启动/再派发均不允许
        assertApiStatus(() -> service.completeTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(key())), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-OLD", "R", "v1", "AWS_AK", "A")), HttpStatus.CONFLICT);

        // 更新有效凭据（新版本，18:00 失效）后再派发
        credential("R", "v2", "AWS_AK", "A", Instant.parse("2026-10-01T18:00:00Z"));
        TaskView redispatched = service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-2", "R", "v2", "AWS_AK", "A"));
        assertThat(redispatched.status()).isEqualTo("DISPATCHED");
        assertThat(redispatched.blockReason()).isNull();
        assertThat(redispatched.blockedAt()).isNull();
        assertThat(redispatched.credential().credentialVersion()).isEqualTo("v2");
        assertThat(redispatched.credential().dispatchStatus()).isEqualTo("DISPATCHED");

        // 再检查通过 → EXECUTING → DONE
        TaskView executing = service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(key()));
        assertThat(executing.status()).isEqualTo("EXECUTING");
        TaskView done = service.completeTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");

        // 查询保留两次派发记录：旧记录 BLOCKED+v1 快照不可变，新记录 EXECUTING+v2
        List<TaskDispatchView> history = service.getTask("INC-1", "T-1").dispatches();
        assertThat(history).hasSize(2);
        assertThat(history.get(0).seq()).isEqualTo(1);
        assertThat(history.get(0).requestId()).isEqualTo("REQ-1");
        assertThat(history.get(0).credentialVersion()).isEqualTo("v1");
        assertThat(history.get(0).region()).isEqualTo("A");
        assertThat(history.get(0).expiresAt()).isEqualTo(EXPIRES_1200);
        assertThat(history.get(0).status()).isEqualTo("BLOCKED");
        assertThat(history.get(0).blockReason()).isEqualTo("CREDENTIAL_EXPIRED");
        assertThat(history.get(1).seq()).isEqualTo(2);
        assertThat(history.get(1).requestId()).isEqualTo("REQ-2");
        assertThat(history.get(1).credentialVersion()).isEqualTo("v2");
        assertThat(history.get(1).status()).isEqualTo("EXECUTING");
    }

    @Test
    void dispatch_rejectionBranches() {
        commanding("INC-1", "alice");
        createTask("INC-1", "T-1");
        credential("R", "v1", "AWS_AK", "A", EXPIRES_1200);

        // 凭据不存在
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-X", "R", "v9", "AWS_AK", "A")), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-X", "R9", "v1", "AWS_AK", "A")), HttpStatus.NOT_FOUND);
        // 类型不匹配
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-X", "R", "v1", "AZURE_SP", "A")), HttpStatus.CONFLICT);
        // 区域不覆盖
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-X", "R", "v1", "AWS_AK", "B")), HttpStatus.CONFLICT);
        // 派发时已过期
        ((ControllableClock) clock).setInstant(AT_1200);
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-X", "R", "v1", "AWS_AK", "A")), HttpStatus.CONFLICT);
        // 派发时已撤销
        ((ControllableClock) clock).setInstant(AT_1159);
        service.revokeCredential("R", "v1", "alice",
                new com.example.starter.incident.dto.Requests.CredentialRevokeRequest(key()));
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-X", "R", "v1", "AWS_AK", "A")), HttpStatus.CONFLICT);
        // 失败不占用 requestId：撤销后换新版本同 requestId 可成功
        credential("R", "v2", "AWS_AK", "A", Instant.parse("2026-10-01T18:00:00Z"));
        TaskView ok = service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-X", "R", "v2", "AWS_AK", "A"));
        assertThat(ok.status()).isEqualTo("DISPATCHED");
        assertThat(ok.credential().credentialVersion()).isEqualTo("v2");
    }

    @Test
    void start_revokedCredential_blocks() {
        commanding("INC-1", "alice");
        createTask("INC-1", "T-1");
        credential("R", "v1", "AWS_AK", "A", Instant.parse("2026-10-01T18:00:00Z"));

        ((ControllableClock) clock).setInstant(AT_1159);
        service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-1", "R", "v1", "AWS_AK", "A"));
        // 派发后、执行前撤销
        service.revokeCredential("R", "v1", "alice",
                new com.example.starter.incident.dto.Requests.CredentialRevokeRequest(key()));
        TaskView blocked = service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(key()));
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.blockReason()).isEqualTo("CREDENTIAL_REVOKED");
        assertThat(blocked.blockedAt()).isEqualTo(AT_1159);
        assertThat(blocked.dispatches().get(0).blockReason()).isEqualTo("CREDENTIAL_REVOKED");

        // 换新凭据再派发可恢复；撤销的旧版本仍不得用于解锁
        credential("R", "v2", "AWS_AK", "A", Instant.parse("2026-10-01T18:00:00Z"));
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-2", "R", "v1", "AWS_AK", "A")), HttpStatus.CONFLICT);
        TaskView redispatched = service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-3", "R", "v2", "AWS_AK", "A"));
        assertThat(redispatched.status()).isEqualTo("DISPATCHED");
    }

    @Test
    void dispatch_requiresCommander_andTaskExists_andOpenOrBlocked() {
        commanding("INC-1", "alice");
        createTask("INC-1", "T-1");
        credential("R", "v1", "AWS_AK", "A", EXPIRES_1200);

        // 非当前指挥人
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "bob",
                dispatchReq("REQ-1", "R", "v1", "AWS_AK", "A")), HttpStatus.CONFLICT);
        // 任务/事件不存在
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-9", "alice",
                dispatchReq("REQ-1", "R", "v1", "AWS_AK", "A")), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.dispatchTask("INC-9", "T-1", "alice",
                dispatchReq("REQ-1", "R", "v1", "AWS_AK", "A")), HttpStatus.NOT_FOUND);

        service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-1", "R", "v1", "AWS_AK", "A"));
        // DISPATCHED 不能重复派发
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-2", "R", "v1", "AWS_AK", "A")), HttpStatus.CONFLICT);

        // 进入 EXECUTING 后不能再派发
        service.startTaskExecution("INC-1", "T-1", "alice", new TaskStartRequest(key()));
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-2", "R", "v1", "AWS_AK", "A")), HttpStatus.CONFLICT);
        service.completeTask("INC-1", "T-1", "alice", new TaskActionRequest(key()));
        // 终态不能派发
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-2", "R", "v1", "AWS_AK", "A")), HttpStatus.CONFLICT);
    }

    @Test
    void requestId_replayFirstTaskAndVersion_changeResource_conflict() {
        commanding("INC-1", "alice");
        createTask("INC-1", "T-1");
        createTask("INC-1", "T-2");
        credential("R", "v1", "AWS_AK", "A", EXPIRES_1200);
        credential("R2", "v1", "AWS_AK", "A", EXPIRES_1200);

        TaskDispatchRequest first = dispatchReq("REQ-IDEM", "R", "v1", "AWS_AK", "A");
        TaskView view = service.dispatchTask("INC-1", "T-1", "alice", first);
        assertThat(view.credential().credentialVersion()).isEqualTo("v1");
        // 完全相同重放：首次任务与凭据版本
        TaskView replay = service.dispatchTask("INC-1", "T-1", "alice", first);
        assertThat(replay.taskKey()).isEqualTo("T-1");
        assertThat(replay.credential().resourceKey()).isEqualTo("R");
        assertThat(replay.credential().credentialVersion()).isEqualTo("v1");
        assertThat(replay.status()).isEqualTo("DISPATCHED");
        // 只产生一条派发记录
        Integer dispatchRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_dispatches WHERE request_id = 'REQ-IDEM'",
                Integer.class);
        assertThat(dispatchRows).isEqualTo(1);

        // 换资源 → 409
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-IDEM", "R2", "v1", "AWS_AK", "A")), HttpStatus.CONFLICT);
        // 换版本 → 409
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-IDEM", "R", "v2", "AWS_AK", "A")), HttpStatus.CONFLICT);
        // 换任务 → 409
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-2", "alice",
                dispatchReq("REQ-IDEM", "R", "v1", "AWS_AK", "A")), HttpStatus.CONFLICT);
        // 换区域/类型 → 409
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-IDEM", "R", "v1", "AWS_AK", "B")), HttpStatus.CONFLICT);
        assertApiStatus(() -> service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-IDEM", "R", "v1", "AZURE_SP", "A")), HttpStatus.CONFLICT);
    }

    @Test
    void startAndComplete_commandKey_idempotency() {
        commanding("INC-1", "alice");
        createTask("INC-1", "T-1");
        credential("R", "v1", "AWS_AK", "A", Instant.parse("2026-10-01T18:00:00Z"));
        service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-1", "R", "v1", "AWS_AK", "A"));

        // start 同键重放首次结果
        String startKey = key();
        TaskView first = service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(startKey));
        TaskView replay = service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(startKey));
        assertThat(replay).isEqualTo(first);
        assertThat(service.getTask("INC-1", "T-1").status()).isEqualTo("EXECUTING");
        // 新键重复启动 → 409
        assertApiStatus(() -> service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(key())), HttpStatus.CONFLICT);

        String completeKey = key();
        TaskView done = service.completeTask("INC-1", "T-1", "alice",
                new TaskActionRequest(completeKey));
        TaskView doneReplay = service.completeTask("INC-1", "T-1", "alice",
                new TaskActionRequest(completeKey));
        assertThat(doneReplay).isEqualTo(done);
    }

    @Test
    void start_onUndispatchedOrBlocked_statusGates() {
        commanding("INC-1", "alice");
        createTask("INC-1", "T-1");
        credential("R", "v1", "AWS_AK", "A", EXPIRES_1200);

        // 未派发不能启动
        assertApiStatus(() -> service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(key())), HttpStatus.CONFLICT);

        ((ControllableClock) clock).setInstant(AT_1159);
        service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-1", "R", "v1", "AWS_AK", "A"));
        // DISPATCHED 可以取消
        TaskView cancelled = service.cancelTask("INC-1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        // 终态不能启动
        assertApiStatus(() -> service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(key())), HttpStatus.CONFLICT);
    }

    @Test
    void blockedTask_blocksResolveGate_untilDone() {
        commanding("INC-1", "alice");
        createTask("INC-1", "T-1");
        credential("R", "v1", "AWS_AK", "A", EXPIRES_1200);
        ((ControllableClock) clock).setInstant(AT_1159);
        service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-1", "R", "v1", "AWS_AK", "A"));
        ((ControllableClock) clock).setInstant(AT_1200);
        TaskView blocked = service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(key()));
        assertThat(blocked.status()).isEqualTo("BLOCKED");

        service.changeStatus("INC-1", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "CONTAINED"));
        // BLOCKED 属未终结任务，阻止推进 RESOLVED
        assertApiStatus(() -> service.changeStatus("INC-1", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "RESOLVED")),
                HttpStatus.CONFLICT);

        // 换新凭据走完流程后可解决
        credential("R", "v2", "AWS_AK", "A", Instant.parse("2026-10-01T18:00:00Z"));
        service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-2", "R", "v2", "AWS_AK", "A"));
        service.startTaskExecution("INC-1", "T-1", "alice", new TaskStartRequest(key()));
        service.completeTask("INC-1", "T-1", "alice", new TaskActionRequest(key()));
        assertThat(service.changeStatus("INC-1", "alice",
                new com.example.starter.incident.dto.Requests.StatusRequest(key(), "RESOLVED"))
                .status()).isEqualTo("RESOLVED");
    }

    @Test
    void expiredBoundary_nowEqualsExpiresAt_isExpired() {
        commanding("INC-1", "alice");
        createTask("INC-1", "T-1");
        credential("R", "v1", "AWS_AK", "A", EXPIRES_1200);

        // 失效时刻前一秒派发通过
        ((ControllableClock) clock).setInstant(AT_1200.minusSeconds(1));
        TaskView dispatched = service.dispatchTask("INC-1", "T-1", "alice",
                dispatchReq("REQ-1", "R", "v1", "AWS_AK", "A"));
        assertThat(dispatched.status()).isEqualTo("DISPATCHED");
        // 恰好失效时刻：执行前检查判定过期
        ((ControllableClock) clock).setInstant(AT_1200);
        TaskView blocked = service.startTaskExecution("INC-1", "T-1", "alice",
                new TaskStartRequest(key()));
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.blockReason()).isEqualTo("CREDENTIAL_EXPIRED");
    }
}
