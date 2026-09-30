package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.CredentialRegisterRequest;
import com.example.starter.incident.dto.Requests.CredentialRevokeRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskDispatchRequest;
import com.example.starter.incident.dto.Responses.DispatchView;
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
 * 资源凭据与任务派发/执行前复检测试（真实 H2 内存库，MODE=MySQL）。
 * 使用可控 UTC 时钟精确覆盖“11:59 派发通过、12:00 复检阻断、更新凭据后再派发”主场景，
 * 以及撤销阻断、类型/区域拒绝、有效期边界、requestId 幂等、旧凭据不得自动解锁、
 * 查询快照与撤销/执行启动并发裁决等边界。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class CredentialDispatchServiceTest {

    private static final Instant T1159 = Instant.parse("2026-09-30T11:59:00Z");
    private static final Instant T1200 = Instant.parse("2026-09-30T12:00:00Z");
    private static final Instant T1300 = Instant.parse("2026-09-30T13:00:00Z");
    private static final String RESOURCE = "RES-R";
    private static final String REGION = "A";
    private static final String CRED_TYPE = "TOKEN";

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private ControllableClock controllable;

    @BeforeEach
    void clean() {
        controllable = (ControllableClock) clock;
        controllable.setInstant(T1159);
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM task_dispatches");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM resource_credentials");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private static String reqId() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "核心链路故障", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void register(String actor, String resource, String type, String region,
                          Instant expiresAt) {
        service.registerCredential(actor, new CredentialRegisterRequest(key(), resource, type,
                region, expiresAt));
    }

    private TaskView newTask(String incidentKey, String actor, String taskKey) {
        return service.createTask(incidentKey, actor, new TaskCreateRequest(key(), taskKey,
                "NET", "切换区域 A 资源", RESOURCE, REGION, List.of()));
    }

    private TaskView dispatch(String incidentKey, String taskKey, String actor, String requestId) {
        return service.dispatchTask(incidentKey, taskKey, actor,
                new TaskDispatchRequest(requestId, RESOURCE, CRED_TYPE));
    }

    private static void assertApiStatus(ThrowingCallable call, HttpStatus status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status()).isEqualTo(status));
    }

    @Test
    void expiryScenario_dispatchBeforeDeadline_blocksAtDeadline_thenRedispatch() {
        commanding("INC-E1", "alice");
        newTask("INC-E1", "alice", "T-1");
        // 资源 R 凭据覆盖区域 A，12:00（UTC）失效
        register("alice", RESOURCE, CRED_TYPE, REGION, T1200);

        // 11:59 派发可通过，记录凭据版本 v1
        TaskView dispatched = dispatch("INC-E1", "T-1", "alice", reqId());
        assertThat(dispatched.status()).isEqualTo("DISPATCHED");
        assertThat(dispatched.credentialVersion()).isEqualTo(1);
        assertThat(dispatched.blockReason()).isNull();

        // 12:00 执行前检查：有效期已结束 → BLOCKED，不能直接完成
        controllable.setInstant(T1200);
        TaskView blocked = service.startTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.credentialVersion()).isEqualTo(1);
        assertThat(blocked.blockReason()).isEqualTo(TaskDispatch.REASON_EXPIRED);
        assertApiStatus(() -> service.completeTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // BLOCKED 再次执行启动同样被拒绝，旧凭据不会自动解锁
        assertApiStatus(() -> service.startTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key())), HttpStatus.CONFLICT);
        // 仍只有旧凭据时重新派发也被拒绝
        assertApiStatus(() -> dispatch("INC-E1", "T-1", "alice", reqId()), HttpStatus.CONFLICT);

        // 更新有效凭据 v2（13:00 失效）后用新 requestId 重新派发
        register("alice", RESOURCE, CRED_TYPE, REGION, T1300);
        TaskView redispatched = dispatch("INC-E1", "T-1", "alice", reqId());
        assertThat(redispatched.status()).isEqualTo("DISPATCHED");
        assertThat(redispatched.credentialVersion()).isEqualTo(2);
        assertThat(redispatched.blockReason()).isNull();

        // 执行前检查通过 → EXECUTING → DONE
        TaskView executing = service.startTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(executing.status()).isEqualTo("EXECUTING");
        TaskView done = service.completeTask("INC-E1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(done.status()).isEqualTo("DONE");

        // 查询展示状态、凭据版本、阻断原因与全部派发记录；历史记录保持当时区域与凭据依据
        TaskView queried = service.getTask("INC-E1", "T-1");
        assertThat(queried.status()).isEqualTo("DONE");
        assertThat(queried.credentialVersion()).isEqualTo(2);
        assertThat(queried.blockReason()).isNull();
        List<DispatchView> history = queried.dispatches();
        assertThat(history).hasSize(2);
        DispatchView first = history.get(0);
        assertThat(first.credentialVersion()).isEqualTo(1);
        assertThat(first.region()).isEqualTo(REGION);
        assertThat(first.credentialExpiresAt()).isEqualTo(T1200);
        assertThat(first.status()).isEqualTo("BLOCKED");
        assertThat(first.blockReason()).isEqualTo(TaskDispatch.REASON_EXPIRED);
        assertThat(first.blockedAt()).isEqualTo(T1200);
        DispatchView second = history.get(1);
        assertThat(second.credentialVersion()).isEqualTo(2);
        assertThat(second.status()).isEqualTo("DISPATCHED");
        assertThat(second.blockReason()).isNull();
    }

    @Test
    void revocation_blocksPendingTask_andNewVersionRequired() {
        commanding("INC-R1", "alice");
        newTask("INC-R1", "alice", "T-1");
        register("alice", RESOURCE, CRED_TYPE, REGION, T1300);
        dispatch("INC-R1", "T-1", "alice", reqId());

        // 撤销 v1 后执行前检查 → BLOCKED（CREDENTIAL_REVOKED）
        service.revokeCredential("alice", new CredentialRevokeRequest(key(), RESOURCE, 1));
        TaskView blocked = service.startTask("INC-R1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.blockReason()).isEqualTo(TaskDispatch.REASON_REVOKED);
        assertThat(blocked.credentialVersion()).isEqualTo(1);

        // 撤销不可逆：重复撤销 409；最新版本仍为已撤销版本，重新派发 409
        assertApiStatus(() -> service.revokeCredential("alice",
                new CredentialRevokeRequest(key(), RESOURCE, 1)), HttpStatus.CONFLICT);
        assertApiStatus(() -> dispatch("INC-R1", "T-1", "alice", reqId()), HttpStatus.CONFLICT);

        // 登记新有效凭据后重新派发、执行、完成
        register("alice", RESOURCE, CRED_TYPE, REGION, T1300);
        TaskView redispatched = dispatch("INC-R1", "T-1", "alice", reqId());
        assertThat(redispatched.credentialVersion()).isEqualTo(2);
        TaskView executing = service.startTask("INC-R1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(executing.status()).isEqualTo("EXECUTING");
        // 被取代的旧派发记录（v1 已 BLOCKED 保持 BLOCKED，不被改写）
        TaskView view = service.getTask("INC-R1", "T-1");
        assertThat(view.dispatches()).extracting(DispatchView::credentialVersion)
                .containsExactly(1, 2);
        assertThat(view.dispatches().get(0).status()).isEqualTo("BLOCKED");
    }

    @Test
    void dispatch_rejectsTypeRegionMismatchAndExpiredBoundary() {
        commanding("INC-V1", "alice");
        newTask("INC-V1", "alice", "T-1");
        // 无凭据：409，不产生派发记录，任务保持 OPEN
        assertApiStatus(() -> dispatch("INC-V1", "T-1", "alice", reqId()), HttpStatus.CONFLICT);
        // 类型不匹配
        register("alice", RESOURCE, "OTHER", REGION, T1300);
        assertApiStatus(() -> dispatch("INC-V1", "T-1", "alice", reqId()), HttpStatus.CONFLICT);
        // 区域不覆盖（最新版本 v2 区域 B）
        register("alice", RESOURCE, CRED_TYPE, "B", T1300);
        assertApiStatus(() -> dispatch("INC-V1", "T-1", "alice", reqId()), HttpStatus.CONFLICT);
        // 恰好 12:00 失效：当前 12:00 即已过期，派发拒绝
        controllable.setInstant(T1200);
        register("alice", RESOURCE, CRED_TYPE, REGION, T1200);
        assertApiStatus(() -> dispatch("INC-V1", "T-1", "alice", reqId()), HttpStatus.CONFLICT);
        assertThat(service.getTask("INC-V1", "T-1").status()).isEqualTo("OPEN");
        assertThat(service.getTask("INC-V1", "T-1").dispatches()).isEmpty();
        // 11:59:59 对同一 12:00 失效凭据仍可派发
        controllable.setInstant(T1200.minusSeconds(1));
        TaskView dispatched = dispatch("INC-V1", "T-1", "alice", reqId());
        assertThat(dispatched.status()).isEqualTo("DISPATCHED");
        assertThat(dispatched.credentialVersion()).isEqualTo(3);
    }

    @Test
    void dispatchRequestId_replayFirstVersion_changeResourceConflicts() {
        commanding("INC-I1", "alice");
        newTask("INC-I1", "alice", "T-1");
        register("alice", RESOURCE, CRED_TYPE, REGION, T1300);
        String requestId = reqId();
        TaskView first = dispatch("INC-I1", "T-1", "alice", requestId);
        assertThat(first.credentialVersion()).isEqualTo(1);

        // 同 requestId 重放：返回首次任务与凭据版本，不新增派发记录
        TaskView replay = dispatch("INC-I1", "T-1", "alice", requestId);
        assertThat(replay).isEqualTo(first);
        TaskView view = service.getTask("INC-I1", "T-1");
        assertThat(view.dispatches()).hasSize(1);

        // 同 requestId 换资源 → 409
        assertApiStatus(() -> service.dispatchTask("INC-I1", "T-1", "alice",
                new TaskDispatchRequest(requestId, "RES-OTHER", CRED_TYPE)), HttpStatus.CONFLICT);
        // 同 requestId 改凭据类型 → 409
        assertApiStatus(() -> service.dispatchTask("INC-I1", "T-1", "alice",
                new TaskDispatchRequest(requestId, RESOURCE, "OTHER")), HttpStatus.CONFLICT);
        // 阻断后同 requestId 仍是重放（不会重新派发）；必须换新 requestId
        service.revokeCredential("alice", new CredentialRevokeRequest(key(), RESOURCE, 1));
        TaskView blocked = service.startTask("INC-I1", "T-1", "alice",
                new TaskActionRequest(key()));
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        TaskView replayBlocked = dispatch("INC-I1", "T-1", "alice", requestId);
        assertThat(replayBlocked.status()).isEqualTo("BLOCKED");
        assertThat(service.getTask("INC-I1", "T-1").dispatches()).hasSize(1);
        register("alice", RESOURCE, CRED_TYPE, REGION, T1300);
        TaskView redispatched = dispatch("INC-I1", "T-1", "alice", reqId());
        assertThat(redispatched.status()).isEqualTo("DISPATCHED");
        assertThat(redispatched.credentialVersion()).isEqualTo(2);
    }

    @Test
    void credentialRegistration_versionsAndQueries() {
        // 版本号自增；查询按版本升序；撤销后状态可见
        service.registerCredential("alice", new CredentialRegisterRequest(key(), RESOURCE,
                CRED_TYPE, REGION, T1200));
        service.registerCredential("bob", new CredentialRegisterRequest(key(), RESOURCE,
                CRED_TYPE, REGION, T1300));
        assertThat(service.listCredentials(RESOURCE)).extracting(v -> v.version())
                .containsExactly(1, 2);
        service.revokeCredential("alice", new CredentialRevokeRequest(key(), RESOURCE, 1));
        assertThat(service.listCredentials(RESOURCE).get(0).status()).isEqualTo("REVOKED");
        assertThat(service.listCredentials(RESOURCE).get(0).revokedAt()).isEqualTo(T1159);
        assertApiStatus(() -> service.listCredentials("RES-404"), HttpStatus.NOT_FOUND);
        assertApiStatus(() -> service.revokeCredential("alice",
                new CredentialRevokeRequest(key(), RESOURCE, 99)), HttpStatus.NOT_FOUND);
    }

    @Test
    void concurrentRevokeAndExecute_decidedByCommittedState() throws Exception {
        commanding("INC-C1", "alice");
        newTask("INC-C1", "alice", "T-1");
        register("alice", RESOURCE, CRED_TYPE, REGION, T1300);
        dispatch("INC-C1", "T-1", "alice", reqId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Object> execute = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return service.startTask("INC-C1", "T-1", "alice",
                            new TaskActionRequest(key()));
                } catch (Exception e) {
                    return e;
                }
            };
            Callable<Object> revoke = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                try {
                    return service.revokeCredential("alice",
                            new CredentialRevokeRequest(key(), RESOURCE, 1));
                } catch (Exception e) {
                    return e;
                }
            };
            Future<Object> f1 = pool.submit(execute);
            Future<Object> f2 = pool.submit(revoke);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            Object executeResult = f1.get(30, TimeUnit.SECONDS);
            Object revokeResult = f2.get(30, TimeUnit.SECONDS);

            // 撤销本身总会成功（启动时凭据仍 ACTIVE）
            assertThat(revokeResult).doesNotHaveSameClassAs(ApiException.class);
            TaskView task = service.getTask("INC-C1", "T-1");
            if (task.status().equals("BLOCKED")) {
                // 撤销先提交：执行前复检只依据已提交状态 → 阻断
                assertThat(task.blockReason()).isEqualTo(TaskDispatch.REASON_REVOKED);
                assertThat(executeResult).isInstanceOf(TaskView.class);
                assertThat(((TaskView) executeResult).status()).isEqualTo("BLOCKED");
            } else {
                // 执行先提交：任务已合法进入 EXECUTING，随后撤销才生效，任务不被回拉为 BLOCKED
                assertThat(task.status()).isEqualTo("EXECUTING");
                assertThat(executeResult).isInstanceOf(TaskView.class);
                assertThat(((TaskView) executeResult).status()).isEqualTo("EXECUTING");
            }
            // 无论提交顺序如何，凭据最终 REVOKED 且任务绝不处于 DONE
            assertThat(service.listCredentials(RESOURCE).get(0).status()).isEqualTo("REVOKED");
            assertThat(task.status()).isIn("BLOCKED", "EXECUTING");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentExecuteStarts_singleTransition() throws Exception {
        commanding("INC-C2", "alice");
        newTask("INC-C2", "alice", "T-1");
        register("alice", RESOURCE, CRED_TYPE, REGION, T1300);
        dispatch("INC-C2", "T-1", "alice", reqId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Object>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return service.startTask("INC-C2", "T-1", "alice",
                                new TaskActionRequest(key()));
                    } catch (Exception e) {
                        return e;
                    }
                });
            }
            List<Future<Object>> futures = new java.util.ArrayList<>();
            for (Callable<Object> c : calls) {
                futures.add(pool.submit(c));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Object> f : futures) {
                Object result = f.get(30, TimeUnit.SECONDS);
                assertThat(result).isInstanceOfSatisfying(TaskView.class,
                        v -> assertThat(v.status()).isEqualTo("EXECUTING"));
            }
        } finally {
            pool.shutdownNow();
        }
        // 仅发生一次 DISPATCHED→EXECUTING：执行启动人/时刻唯一
        TaskView task = service.getTask("INC-C2", "T-1");
        assertThat(task.status()).isEqualTo("EXECUTING");
        Integer executingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_tasks WHERE status = 'EXECUTING' AND executing_at IS NOT NULL",
                Integer.class);
        assertThat(executingCount).isEqualTo(1);
    }
}
