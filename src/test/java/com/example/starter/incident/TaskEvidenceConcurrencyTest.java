package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.EvidenceActionRequest;
import com.example.starter.incident.dto.Requests.EvidenceConfigRequest;
import com.example.starter.incident.dto.Requests.EvidenceUploadRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.EvidenceChainView;
import com.example.starter.incident.dto.Responses.EvidenceRequirementsView;
import com.example.starter.incident.dto.Responses.EvidenceView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 任务验收证据链并发边界测试：证据确认、作废、必需类别变更与任务完成在数据库中串行判定，
 * 同 requestId 并发单次生效，竞争不泄露唯一键异常，最终结果按事务提交顺序保持一致。
 */
@SpringBootTest
class TaskEvidenceConcurrencyTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM task_evidence");
        jdbc.update("DELETE FROM task_required_categories");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "REQ-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void openTaskWithPhoto(String incidentKey, String taskKey, String commander) {
        service.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), taskKey, "G", "处置任务", List.of()));
        service.configureEvidenceRequirements(incidentKey, taskKey, commander,
                new EvidenceConfigRequest(key(), 1L, List.of("PHOTO")));
        service.uploadEvidence(incidentKey, taskKey, "exec-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "现场照片"));
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

    private static long countApiErrors(List<Object> results, HttpStatus status) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == status).count();
    }

    @Test
    void concurrentConfirm_sameEvidence_singleEffect() throws Exception {
        commanding("INC-CC", "alice");
        openTaskWithPhoto("INC-CC", "T-1", "alice");

        // 两名验收人并发确认同一证据（不同 requestId）：恰好一人成功
        List<Object> results = runConcurrently(List.of(
                () -> service.confirmEvidence("INC-CC", "T-1", "EV-1", "rev-1",
                        new EvidenceActionRequest(key())),
                () -> service.confirmEvidence("INC-CC", "T-1", "EV-1", "rev-2",
                        new EvidenceActionRequest(key()))));

        assertThat(results.stream().filter(EvidenceView.class::isInstance).count()).isEqualTo(1);
        assertThat(countApiErrors(results, HttpStatus.CONFLICT)).isEqualTo(1);
        EvidenceChainView chain = service.evidenceChain("INC-CC", "T-1");
        assertThat(chain.evidence()).hasSize(1);
        assertThat(chain.evidence().get(0).status()).isEqualTo("CONFIRMED");
        assertThat(chain.evidence().get(0).confirmedBy()).isIn("rev-1", "rev-2");
    }

    @Test
    void concurrentConfirmAndVoid_serialized() throws Exception {
        commanding("INC-CV", "alice");
        openTaskWithPhoto("INC-CV", "T-1", "alice");

        // 并发确认与作废：串行判定，恰好一个生效
        List<Object> results = runConcurrently(List.of(
                () -> service.confirmEvidence("INC-CV", "T-1", "EV-1", "rev-1",
                        new EvidenceActionRequest(key())),
                () -> service.voidEvidence("INC-CV", "T-1", "EV-1", "exec-1",
                        new EvidenceActionRequest(key()))));

        assertThat(results.stream().filter(EvidenceView.class::isInstance).count()).isEqualTo(1);
        assertThat(countApiErrors(results, HttpStatus.CONFLICT)).isEqualTo(1);
        EvidenceChainView chain = service.evidenceChain("INC-CV", "T-1");
        String status = chain.evidence().get(0).status();
        assertThat(status).isIn("CONFIRMED", "VOIDED");
        if (status.equals("VOIDED")) {
            // 作废使确认失效：完成门禁 422
            assertThatThrownByComplete("INC-CV", "T-1");
        } else {
            TaskView done = service.completeTask("INC-CV", "T-1", "alice",
                    new TaskActionRequest(key()));
            assertThat(done.status()).isEqualTo("DONE");
        }
    }

    private void assertThatThrownByComplete(String incidentKey, String taskKey) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.completeTask(
                incidentKey, taskKey, "alice", new TaskActionRequest(key())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void concurrentConfigAndComplete_serialized() throws Exception {
        commanding("INC-XC", "alice");
        openTaskWithPhoto("INC-XC", "T-1", "alice");
        service.confirmEvidence("INC-XC", "T-1", "EV-1", "rev-1", new EvidenceActionRequest(key()));

        // 并发：必需类别变更（追加 READING）与任务完成
        List<Object> results = runConcurrently(List.of(
                () -> service.configureEvidenceRequirements("INC-XC", "T-1", "alice",
                        new EvidenceConfigRequest(key(), 2L, List.of("PHOTO", "READING"))),
                () -> service.completeTask("INC-XC", "T-1", "alice", new TaskActionRequest(key()))));

        // 竞争不泄露唯一键等底层异常：结果只能是业务视图或 ApiException
        assertThat(results).allSatisfy(r -> assertThat(r)
                .isInstanceOfAny(EvidenceRequirementsView.class, TaskView.class, ApiException.class));
        TaskView task = service.getTask("INC-XC", "T-1");
        EvidenceChainView chain = service.evidenceChain("INC-XC", "T-1");
        if (task.status().equals("DONE")) {
            // 完成先提交：类别变更打在终态任务上，409；版本保持 2，证据已冻结
            assertThat(results.stream().filter(EvidenceRequirementsView.class::isInstance).count())
                    .isZero();
            assertThat(chain.taskVersion()).isEqualTo(2);
            assertThat(chain.evidence().get(0).frozenAt()).isNotNull();
        } else {
            // 类别变更先提交：完成缺少 READING 确认证据，422；任务仍 OPEN，版本升到 3
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(chain.taskVersion()).isEqualTo(3);
            assertThat(countApiErrors(results, HttpStatus.UNPROCESSABLE_ENTITY)).isEqualTo(1);
        }
    }

    @Test
    void concurrentUpload_sameRequestId_singleEffect() throws Exception {
        commanding("INC-CU", "alice");
        openTaskWithPhoto("INC-CU", "T-1", "alice");
        String requestId = key();
        List<Callable<EvidenceView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.uploadEvidence("INC-CU", "T-1", "exec-2",
                    new EvidenceUploadRequest(requestId, "EV-2", "READING", "读数截图")));
        }
        List<Object> results = runConcurrently(tasks);

        List<EvidenceView> successes = results.stream().filter(EvidenceView.class::isInstance)
                .map(EvidenceView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        // 证据只落库一次，requestId 只占一键
        assertThat(service.evidenceChain("INC-CU", "T-1").evidence()).hasSize(2);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, requestId);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentComplete_sameTask_singleDone() throws Exception {
        commanding("INC-CD", "alice");
        openTaskWithPhoto("INC-CD", "T-1", "alice");
        service.confirmEvidence("INC-CD", "T-1", "EV-1", "rev-1", new EvidenceActionRequest(key()));

        // 并发完成同一任务（不同 commandKey）：恰好一次生效
        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-CD", "T-1", "alice", new TaskActionRequest(key())),
                () -> service.completeTask("INC-CD", "T-1", "alice", new TaskActionRequest(key()))));

        assertThat(results.stream().filter(TaskView.class::isInstance).count()).isEqualTo(1);
        assertThat(countApiErrors(results, HttpStatus.CONFLICT)).isEqualTo(1);
        assertThat(service.getTask("INC-CD", "T-1").status()).isEqualTo("DONE");
        // 完成冻结当时证据
        assertThat(service.evidenceChain("INC-CD", "T-1").evidence().get(0).frozenAt()).isNotNull();
    }
}