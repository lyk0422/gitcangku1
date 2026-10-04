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
import com.example.starter.incident.dto.Requests.EvidenceRequirementRequest;
import com.example.starter.incident.dto.Requests.EvidenceUploadRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskActionRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.EvidenceView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 任务验收证据链并发边界测试：证据确认、作废、必需类别变更与任务完成
 * 在事件行锁下串行判定，并发结果按事务提交顺序形成合法终态；
 * 同 requestId 并发单次生效，不泄露唯一键异常。
 */
@SpringBootTest
class IncidentEvidenceConcurrencyTest {

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
        jdbc.update("DELETE FROM incident_task_evidence_requirements");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "证据并发场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private void createTask(String incidentKey, String actor, String taskKey) {
        service.createTask(incidentKey, actor,
                new TaskCreateRequest(key(), taskKey, "G", "任务" + taskKey, List.of()));
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

    private static long countApiStatus(List<Object> results, HttpStatus status) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == status).count();
    }

    @Test
    void concurrentConfirm_sameEvidence_singleSuccess() throws Exception {
        commanding("INC-C1", "alice");
        createTask("INC-C1", "alice", "T-1");
        service.uploadEvidence("INC-C1", "T-1", "exec-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "现场照片"));

        List<Object> results = runConcurrently(List.of(
                () -> service.confirmEvidence("INC-C1", "T-1", "EV-1", "acceptor-1",
                        new EvidenceActionRequest(key())),
                () -> service.confirmEvidence("INC-C1", "T-1", "EV-1", "acceptor-2",
                        new EvidenceActionRequest(key()))));

        // 恰好一名验收人确认成功，另一名 409
        assertThat(results.stream().filter(EvidenceView.class::isInstance).count()).isEqualTo(1);
        assertThat(countApiStatus(results, HttpStatus.CONFLICT)).isEqualTo(1);
        EvidenceView end = service.listEvidences("INC-C1", "T-1").evidences().get(0);
        assertThat(end.status()).isEqualTo("CONFIRMED");
        assertThat(end.confirmedBy()).isIn("acceptor-1", "acceptor-2");
    }

    @Test
    void concurrentCompleteAndVoid_serializedConsistent() throws Exception {
        commanding("INC-C2", "alice");
        createTask("INC-C2", "alice", "T-1");
        service.setEvidenceRequirements("INC-C2", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO"), 1L));
        service.uploadEvidence("INC-C2", "T-1", "exec-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "现场照片"));
        service.confirmEvidence("INC-C2", "T-1", "EV-1", "acceptor-1",
                new EvidenceActionRequest(key()));

        // 并发：完成任务 与 作废唯一确认证据
        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-C2", "T-1", "alice", new TaskActionRequest(key())),
                () -> service.voidEvidence("INC-C2", "T-1", "EV-1", "exec-1",
                        new EvidenceActionRequest(key()))));

        TaskView task = service.getTask("INC-C2", "T-1");
        EvidenceView evidence = service.listEvidences("INC-C2", "T-1").evidences().get(0);
        if (task.status().equals("DONE")) {
            // 完成先提交：证据冻结，作废被拒 409，证据保持 CONFIRMED
            assertThat(results.get(0)).isInstanceOf(TaskView.class);
            assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(evidence.status()).isEqualTo("CONFIRMED");
            assertThat(evidence.confirmedBy()).isEqualTo("acceptor-1");
        } else {
            // 作废先提交：确认失效，完成被拒 422，任务仍 OPEN
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(evidence.status()).isEqualTo("VOID");
            assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(results.get(1)).isInstanceOf(EvidenceView.class);
        }
    }

    @Test
    void concurrentSetRequirementsAndComplete_serializedConsistent() throws Exception {
        commanding("INC-C3", "alice");
        createTask("INC-C3", "alice", "T-1");
        service.setEvidenceRequirements("INC-C3", "T-1", "alice",
                new EvidenceRequirementRequest(key(), List.of("PHOTO"), 1L));
        service.uploadEvidence("INC-C3", "T-1", "exec-1",
                new EvidenceUploadRequest(key(), "EV-1", "PHOTO", "现场照片"));
        service.confirmEvidence("INC-C3", "T-1", "EV-1", "acceptor-1",
                new EvidenceActionRequest(key()));

        // 并发：完成任务 与 变更新必需类别（期望版本 2）
        List<Object> results = runConcurrently(List.of(
                () -> service.completeTask("INC-C3", "T-1", "alice", new TaskActionRequest(key())),
                () -> service.setEvidenceRequirements("INC-C3", "T-1", "alice",
                        new EvidenceRequirementRequest(key(), List.of("READING"), 2L))));

        TaskView task = service.getTask("INC-C3", "T-1");
        if (task.status().equals("DONE")) {
            // 完成先提交：任务终态，类别变更被拒 409
            assertThat(results.get(0)).isInstanceOf(TaskView.class);
            assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(task.requiredEvidenceCategories()).containsExactly("PHOTO");
        } else {
            // 变更先提交：READING 缺证据，完成被拒 422，任务仍 OPEN
            assertThat(task.status()).isEqualTo("OPEN");
            assertThat(task.requiredEvidenceCategories()).containsExactly("READING");
            assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(results.get(1)).isInstanceOf(TaskView.class);
        }
    }

    @Test
    void concurrentUpload_sameRequestId_singleEffect() throws Exception {
        commanding("INC-C4", "alice");
        createTask("INC-C4", "alice", "T-1");
        String requestId = key();
        List<Callable<EvidenceView>> uploads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            uploads.add(() -> service.uploadEvidence("INC-C4", "T-1", "exec-1",
                    new EvidenceUploadRequest(requestId, "EV-1", "PHOTO", "现场照片")));
        }
        List<Object> results = runConcurrently(uploads);

        List<EvidenceView> successes = results.stream().filter(EvidenceView.class::isInstance)
                .map(EvidenceView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，证据只落库一次，requestId 只占一键
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(service.listEvidences("INC-C4", "T-1").evidences()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                requestId);
        assertThat(keyCount).isEqualTo(1);
    }
}