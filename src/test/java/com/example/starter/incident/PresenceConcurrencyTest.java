package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.example.starter.incident.dto.Requests.PresenceCheckInRequest;
import com.example.starter.incident.dto.Requests.PresenceRevokeRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.SupervisionEnableRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCompleteRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.PresenceCredentialView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 双人监护在场凭证并发边界测试：并发完成与撤销/重新签到按事务提交顺序形成合法结果，
 * 不能在一方凭证已失效后完成；同 commandKey 并发签到单次生效。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class PresenceConcurrencyTest {

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

    private void supervisedTask(String incidentKey, String taskKey) {
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        service.createTask(incidentKey, "alice",
                new TaskCreateRequest(key(), taskKey, "OPS", "高危处置", List.of()));
        service.enableSupervision(incidentKey, taskKey, "alice",
                new SupervisionEnableRequest(key(), "op-exec", "op-guard", 1));
    }

    private PresenceCredentialView checkIn(String incidentKey, String taskKey, String actor) {
        return service.checkInPresence(incidentKey, taskKey, actor,
                new PresenceCheckInRequest(key(), 2));
    }

    private TaskView complete(String incidentKey, String taskKey, int execGen, int guardGen) {
        return service.completeTask(incidentKey, taskKey, "alice",
                new TaskCompleteRequest(key(), execGen, guardGen, 2));
    }

    /**
     * 并发提交一批操作并收集结果（成功值或异常）。
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

    @Test
    void concurrentCompleteAndRevoke_cannotCompleteAfterRevoked() throws Exception {
        supervisedTask("INC-CR", "T-1");
        checkIn("INC-CR", "T-1", "op-exec");
        checkIn("INC-CR", "T-1", "op-guard");

        // 并发：完成（引用双方代次 1）与撤销执行人凭证
        List<Object> results = runConcurrently(List.of(
                () -> complete("INC-CR", "T-1", 1, 1),
                () -> service.revokePresence("INC-CR", "T-1", "alice",
                        new PresenceRevokeRequest(key(), "op-exec", 2))));

        TaskView task = service.getTask("INC-CR", "T-1");
        Object completeResult = results.get(0);
        Object revokeResult = results.get(1);
        if ("DONE".equals(task.status())) {
            // 完成先提交：撤销落在 DONE 之后 → 422 非法状态
            assertThat(completeResult).isInstanceOf(TaskView.class);
            assertThat(revokeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        } else {
            // 撤销先提交：完成看到已撤销凭证 → 422，任务仍 OPEN
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(revokeResult).isInstanceOf(PresenceCredentialView.class);
            assertThat(completeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }
    }

    @Test
    void concurrentCompleteAndReCheckIn_cannotCompleteWithStaleGeneration() throws Exception {
        supervisedTask("INC-CS", "T-1");
        checkIn("INC-CS", "T-1", "op-exec");
        checkIn("INC-CS", "T-1", "op-guard");

        // 并发：完成（引用执行人代次 1）与执行人重新签到（推进到代次 2）
        List<Object> results = runConcurrently(List.of(
                () -> complete("INC-CS", "T-1", 1, 1),
                () -> checkIn("INC-CS", "T-1", "op-exec")));

        TaskView task = service.getTask("INC-CS", "T-1");
        Object completeResult = results.get(0);
        Object checkInResult = results.get(1);
        if ("DONE".equals(task.status())) {
            // 完成先提交：DONE 后禁止新签到 → 422
            assertThat(completeResult).isInstanceOf(TaskView.class);
            assertThat(checkInResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        } else {
            // 重新签到先提交：代次 1 已成旧代，完成 422，任务仍 OPEN
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(checkInResult).isInstanceOfSatisfying(PresenceCredentialView.class,
                    v -> assertThat(v.generation()).isEqualTo(2));
            assertThat(completeResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            // 用新代次仍可完成
            assertThat(complete("INC-CS", "T-1", 2, 1).status()).isEqualTo("DONE");
        }
    }

    @Test
    void concurrentCheckIn_sameCommandKey_singleEffect() throws Exception {
        supervisedTask("INC-CK", "T-1");
        String commandKey = key();
        List<Callable<PresenceCredentialView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.checkInPresence("INC-CK", "T-1", "op-exec",
                    new PresenceCheckInRequest(commandKey, 2)));
        }
        List<Object> results = runConcurrently(tasks);

        List<PresenceCredentialView> successes = results.stream()
                .filter(PresenceCredentialView.class::isInstance)
                .map(PresenceCredentialView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致（同一代次、同一签发时刻），且只插入一条凭证
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(successes.get(0).generation()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_presence_credentials WHERE role = 'EXECUTOR'",
                Integer.class);
        assertThat(count).isEqualTo(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentCheckIn_distinctKeys_generationsUnique() throws Exception {
        supervisedTask("INC-CG", "T-1");
        List<Callable<PresenceCredentialView>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> service.checkInPresence("INC-CG", "T-1", "op-exec",
                    new PresenceCheckInRequest(key(), 2)));
        }
        List<Object> results = runConcurrently(tasks);

        List<PresenceCredentialView> successes = results.stream()
                .filter(PresenceCredentialView.class::isInstance)
                .map(PresenceCredentialView.class::cast).toList();
        assertThat(successes).hasSize(4);
        // 事件行锁串行化：代次恰好为 1..4 各一次，无重复、无唯一键泄漏
        assertThat(successes.stream().map(PresenceCredentialView::generation))
                .containsExactlyInAnyOrder(1, 2, 3, 4);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_presence_credentials WHERE role = 'EXECUTOR'",
                Integer.class);
        assertThat(count).isEqualTo(4);
    }
}
