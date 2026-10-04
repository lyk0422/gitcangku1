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
import com.example.starter.incident.dto.Requests.EvidenceRequirementsRequest;
import com.example.starter.incident.dto.Requests.EvidenceUploadRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.TaskEvidenceView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 任务验收证据链并发边界测试：确认/作废/必需类别变更与任务完成在数据库串行判定，
 * 同 requestId 并发单次生效，竞争不泄露唯一键异常，最终业务状态一致。
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
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_evidences");
        jdbc.update("DELETE FROM incident_task_required_categories");
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

    private void openTaskWithPhoto(String incidentKey, String commander) {
        service.createTask(incidentKey, commander,
                new TaskCreateRequest(key(), "T-1", "G", "合成任务", List.of()));
        service.setEvidenceRequirements(incidentKey, "T-1", commander,
                new EvidenceRequirementsRequest(key(), 0, List.of("PHOTO")));
        service.uploadEvidence(incidentKey, "T-1", "executor-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "照片"));
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

    private static long countByStatus(List<Object> results, HttpStatus status) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == status).count();
    }

    @Test
    void concurrentConfirmAndComplete_serializedVerdict() throws Exception {
        commanding("INC-A", "alice");
        openTaskWithPhoto("INC-A", "alice");

        // 并发：验收人确认证据 与 指挥人完成任务（同一事件行串行判定）
        List<Object> results = runConcurrently(List.of(
                () -> service.confirmEvidence("INC-A", "T-1", "EV-1", "verifier-1",
                        new EvidenceActionRequest(key())),
                () -> service.completeTask("INC-A", "T-1", "alice",
                        new TaskActionRequest(key()))));

        // 确认必然成功；完成结果取决于其判定时确认是否已提交
        assertThat(results.get(0)).isInstanceOf(TaskEvidenceView.class);
        TaskView task = service.getTask("INC-A", "T-1");
        TaskEvidenceView evidence = service.evidenceChain("INC-A", "T-1").evidences().get(0);
        if (task.status().equals("DONE")) {
            // 确认先提交：完成成功，证据已确认且被完成冻结
            assertThat(results.get(1)).isInstanceOf(TaskView.class);
            assertThat(evidence.status()).isEqualTo("CONFIRMED");
            assertThat(evidence.frozen()).isTrue();
        } else {
            // 完成先判定：证据尚未确认，422 且任务仍 OPEN，证据后续确认成功
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(evidence.status()).isEqualTo("CONFIRMED");
            assertThat(evidence.frozen()).isFalse();
        }
    }

    @Test
    void concurrentVoidAndComplete_exactlyOneWins() throws Exception {
        commanding("INC-B", "alice");
        openTaskWithPhoto("INC-B", "alice");
        service.confirmEvidence("INC-B", "T-1", "EV-1", "verifier-1",
                new EvidenceActionRequest(key()));

        // 并发：作废已确认证据 与 完成任务
        List<Object> results = runConcurrently(List.of(
                () -> service.voidEvidence("INC-B", "T-1", "EV-1", "alice",
                        new EvidenceActionRequest(key())),
                () -> service.completeTask("INC-B", "T-1", "alice",
                        new TaskActionRequest(key()))));

        TaskView task = service.getTask("INC-B", "T-1");
        TaskEvidenceView evidence = service.evidenceChain("INC-B", "T-1").evidences().get(0);
        if (task.status().equals("DONE")) {
            // 完成先提交：证据冻结，作废被拒 409
            assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(evidence.status()).isEqualTo("CONFIRMED");
            assertThat(evidence.frozen()).isTrue();
        } else {
            // 作废先提交：确认失效，完成 422，任务仍 OPEN
            assertThat(results.get(0)).isInstanceOf(TaskEvidenceView.class);
            assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(evidence.status()).isEqualTo("VOIDED");
        }
    }

    @Test
    void concurrentSetRequirements_sameExpectedVersion_singleSuccess() throws Exception {
        commanding("INC-C", "alice");
        service.createTask("INC-C", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "合成任务", List.of()));

        // 并发以相同 expectedTaskVersion=0 设置不同类别：乐观版本控制下恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.setEvidenceRequirements("INC-C", "T-1", "alice",
                        new EvidenceRequirementsRequest(key(), 0, List.of("PHOTO"))),
                () -> service.setEvidenceRequirements("INC-C", "T-1", "alice",
                        new EvidenceRequirementsRequest(key(), 0, List.of("READING")))));

        long successes = results.stream().filter(TaskView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countByStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);
        // 最终版本为 1，类别集合恰为胜出版本
        TaskView task = service.getTask("INC-C", "T-1");
        assertThat(task.requirementVersion()).isEqualTo(1);
        assertThat(task.requiredCategories()).hasSize(1);
        Integer rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_required_categories", Integer.class);
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void concurrentUpload_sameRequestId_singleEffect() throws Exception {
        commanding("INC-D", "alice");
        service.createTask("INC-D", "alice",
                new TaskCreateRequest(key(), "T-1", "G", "合成任务", List.of()));
        String requestId = key();
        List<Callable<TaskEvidenceView>> uploads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            uploads.add(() -> service.uploadEvidence("INC-D", "T-1", "executor-1",
                    new EvidenceUploadRequest(requestId, "EV-1", "PHOTO", "照片")));
        }
        List<Object> results = runConcurrently(uploads);

        List<TaskEvidenceView> successes = results.stream()
                .filter(TaskEvidenceView.class::isInstance)
                .map(TaskEvidenceView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，证据只落库一次，requestId 只占一键
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(service.evidenceChain("INC-D", "T-1").evidences()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                requestId);
        assertThat(keyCount).isEqualTo(1);
    }
}