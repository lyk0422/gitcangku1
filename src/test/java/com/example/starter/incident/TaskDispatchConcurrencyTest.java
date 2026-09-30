package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TaskDispatchRequest;
import com.example.starter.incident.dto.Requests.TaskStartRequest;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 资源凭据并发边界测试（真实 H2 行锁）：
 * 撤销与执行启动竞争时只允许按已提交凭据状态形成一种一致结果（EXECUTING 或 BLOCKED）；
 * 同 requestId 并发派发只生效一次且响应一致；
 * 派发与撤销竞争时只有一个事务能拿到凭据行锁并形成确定结果。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class TaskDispatchConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-10-01T11:00:00Z");
    private static final Instant AT_1159 = Instant.parse("2026-10-01T11:59:00Z");
    private static final Instant EXPIRES_LATER = Instant.parse("2026-10-01T18:00:00Z");

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

    private void dispatchedTask(String incidentKey) {
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        service.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), "T-1", "OPS", "执行操作", List.of()));
        service.registerCredential(new CredentialRegisterRequest(
                key(), "R", "v1", "AWS_AK", "A", EXPIRES_LATER));
        ((ControllableClock) clock).setInstant(AT_1159);
        service.dispatchTask(incidentKey, "T-1", "alice",
                new TaskDispatchRequest("REQ-" + incidentKey, "R", "v1", "AWS_AK", "A"));
    }

    private static List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : tasks) {
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

    @Test
    void concurrentStartAndRevoke_onlyCommittedCredentialStateWins() throws Exception {
        dispatchedTask("INC-C1");

        List<Object> results = runConcurrently(List.of(
                () -> service.startTaskExecution("INC-C1", "T-1", "alice",
                        new TaskStartRequest(key())),
                () -> service.revokeCredential("R", "v1", "alice",
                        new CredentialRevokeRequest(key()))));

        // 竞争结束后只能形成一种与已提交凭据状态一致的结果：
        // 执行启动先拿到凭据行锁 → EXECUTING；撤销先提交 → BLOCKED(CREDENTIAL_REVOKED)
        TaskView finalTask = service.getTask("INC-C1", "T-1");
        String credentialStatus = jdbc.queryForObject(
                "SELECT status FROM resource_credentials WHERE resource_key='R' AND version='v1'",
                String.class);
        Object startResult = results.get(0);
        if ("EXECUTING".equals(finalTask.status())) {
            assertThat(startResult).isInstanceOf(TaskView.class);
            assertThat(((TaskView) startResult).status()).isEqualTo("EXECUTING");
            // 启动先提交；撤销随后生效，凭据为 REVOKED 但任务已进入执行态
            assertThat(credentialStatus).isEqualTo("REVOKED");
            assertThat(finalTask.credential().dispatchStatus()).isEqualTo("EXECUTING");
            assertThat(finalTask.blockReason()).isNull();
        } else {
            assertThat(finalTask.status()).isEqualTo("BLOCKED");
            assertThat(finalTask.blockReason()).isEqualTo("CREDENTIAL_REVOKED");
            assertThat(credentialStatus).isEqualTo("REVOKED");
            assertThat(finalTask.credential().dispatchStatus()).isEqualTo("BLOCKED");
            // 执行启动返回的视图也是 BLOCKED（状态转移随事务提交，非异常）
            assertThat(startResult).isInstanceOfSatisfying(TaskView.class,
                    v -> assertThat(v.status()).isEqualTo("BLOCKED"));
        }

        // 无论谁先提交，都不存在“既 BLOCKED 又允许完成”的矛盾；BLOCKED 只能换新凭据再派发
        if ("BLOCKED".equals(finalTask.status())) {
            service.registerCredential(new CredentialRegisterRequest(
                    key(), "R", "v2", "AWS_AK", "A", EXPIRES_LATER));
            TaskView redispatched = service.dispatchTask("INC-C1", "T-1", "alice",
                    new TaskDispatchRequest(key(), "R", "v2", "AWS_AK", "A"));
            assertThat(redispatched.status()).isEqualTo("DISPATCHED");
            Integer dispatchCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM task_dispatches WHERE task_id ="
                            + " (SELECT id FROM incident_tasks WHERE task_key='T-1')",
                    Integer.class);
            assertThat(dispatchCount).isEqualTo(2);
        }
    }

    @Test
    void concurrentDispatch_sameRequestId_singleEffect() throws Exception {
        service.report(new ReportRequest("INC-C2", "S2", "并发场景", "reporter-1"));
        service.takeover("INC-C2", "alice", new TakeoverRequest(key()));
        service.createTask("INC-C2", "alice",
                new TaskCreateRequest(key(), "T-1", "OPS", "执行操作", List.of()));
        service.registerCredential(new CredentialRegisterRequest(
                key(), "R", "v1", "AWS_AK", "A", EXPIRES_LATER));
        ((ControllableClock) clock).setInstant(AT_1159);

        String requestId = "REQ-IDEM-C2";
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.dispatchTask("INC-C2", "T-1", "alice",
                    new TaskDispatchRequest(requestId, "R", "v1", "AWS_AK", "A")));
        }
        List<Object> results = runConcurrently(tasks);

        // 全部拿到一致的首次响应（占位唯一键串行化，提交后重放），无“处理中”冲突残留
        List<TaskView> views = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(TaskView.class);
            views.add((TaskView) result);
        }
        assertThat(views).allSatisfy(v -> {
            assertThat(v.status()).isEqualTo("DISPATCHED");
            assertThat(v.credential().resourceKey()).isEqualTo("R");
            assertThat(v.credential().credentialVersion()).isEqualTo("v1");
            assertThat(v.dispatches()).hasSize(1);
        });
        Integer dispatchRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_dispatches WHERE request_id = ?", Integer.class,
                requestId);
        assertThat(dispatchRows).isEqualTo(1);
        Integer seqRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_dispatches WHERE task_id ="
                        + " (SELECT id FROM incident_tasks WHERE task_key='T-1')",
                Integer.class);
        assertThat(seqRows).isEqualTo(1);
    }

    @Test
    void concurrentDispatchAndRevoke_eitherRejectedOrBlockedConsistently() throws Exception {
        service.report(new ReportRequest("INC-C3", "S2", "并发场景", "reporter-1"));
        service.takeover("INC-C3", "alice", new TakeoverRequest(key()));
        service.createTask("INC-C3", "alice",
                new TaskCreateRequest(key(), "T-1", "OPS", "执行操作", List.of()));
        service.registerCredential(new CredentialRegisterRequest(
                key(), "R", "v1", "AWS_AK", "A", EXPIRES_LATER));
        ((ControllableClock) clock).setInstant(AT_1159);

        // 派发与撤销同资源凭据竞争
        List<Object> results = runConcurrently(List.of(
                () -> service.dispatchTask("INC-C3", "T-1", "alice",
                        new TaskDispatchRequest("REQ-C3", "R", "v1", "AWS_AK", "A")),
                () -> service.revokeCredential("R", "v1", "alice",
                        new CredentialRevokeRequest(key()))));

        String credentialStatus = jdbc.queryForObject(
                "SELECT status FROM resource_credentials WHERE resource_key='R' AND version='v1'",
                String.class);
        assertThat(credentialStatus).isEqualTo("REVOKED");
        TaskView task = service.getTask("INC-C3", "T-1");
        Object dispatchResult = results.get(0);
        if ("DISPATCHED".equals(task.status())) {
            // 派发先提交：成功；凭据随后撤销，执行前检查必须阻断
            assertThat(dispatchResult).isInstanceOf(TaskView.class);
            TaskView blocked = service.startTaskExecution("INC-C3", "T-1", "alice",
                    new TaskStartRequest(key()));
            assertThat(blocked.status()).isEqualTo("BLOCKED");
            assertThat(blocked.blockReason()).isEqualTo("CREDENTIAL_REVOKED");
        } else {
            // 撤销先提交：派发被拒绝（409），任务保持 OPEN，不留派发记录
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(dispatchResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            Integer dispatchRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM task_dispatches", Integer.class);
            assertThat(dispatchRows).isZero();
        }
    }
}
