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

import com.example.starter.incident.dto.Requests.MergeRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Responses.MergeView;
import com.example.starter.incident.dto.Responses.TaskView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 重复事件合并并发边界测试：验证并发合并按事务提交顺序裁决——
 * 同一事件最多被合并一次、禁止环形合并链、版本裁决唯一成功、
 * 同 commandKey 并发单次生效，以及合并与任务创建并发时最终图无环。
 */
@SpringBootTest
class IncidentMergeConcurrencyTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_merges");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private static String mergeKey() {
        return "MRG-" + UUID.randomUUID();
    }

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
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

    private static long countConflicts(List<Object> results) {
        return results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status() == HttpStatus.CONFLICT).count();
    }

    @Test
    void concurrentMerge_samePair_singleSuccess() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        // 并发合并同一对事件（不同 commandKey/mergeKey）：行锁串行化后恰一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.merge("alice",
                        new MergeRequest(key(), mergeKey(), "INC-A", "INC-B", 0L, 0L)),
                () -> service.merge("alice",
                        new MergeRequest(key(), mergeKey(), "INC-A", "INC-B", 0L, 0L))));

        assertThat(results.stream().filter(MergeView.class::isInstance).count()).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        // 同一事件最多被合并一次：一条合并记录、一条 MERGED 流转、双方版本各加一
        assertThat(service.listMerges().merges()).hasSize(1);
        assertThat(service.get("INC-B").status()).isEqualTo("MERGED");
        assertThat(service.get("INC-B").version()).isEqualTo(1L);
        assertThat(service.get("INC-A").version()).isEqualTo(1L);
        Integer mergedTransitions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_status_history WHERE to_status = 'MERGED'",
                Integer.class);
        assertThat(mergedTransitions).isEqualTo(1);
    }

    @Test
    void concurrentMerge_ring_prevented() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        // 并发反向合并：A←B 与 B←A 互成环，恰一个成功，禁止环形合并链
        List<Object> results = runConcurrently(List.of(
                () -> service.merge("alice",
                        new MergeRequest(key(), mergeKey(), "INC-A", "INC-B", 0L, 0L)),
                () -> service.merge("alice",
                        new MergeRequest(key(), mergeKey(), "INC-B", "INC-A", 0L, 0L))));

        assertThat(results.stream().filter(MergeView.class::isInstance).count()).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        assertThat(service.listMerges().merges()).hasSize(1);
        // 恰一个事件进入 MERGED，另一个仍是存续事件
        String statusA = service.get("INC-A").status();
        String statusB = service.get("INC-B").status();
        assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder("MERGED", "COMMANDING");
    }

    @Test
    void concurrentMerge_sharedSurviving_versionArbitrates() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        commanding("INC-C", "alice");
        // 并发合并到同一存续事件（期望版本均为 0）：先提交者成功并将版本推进到 1，
        // 后提交者版本不匹配 409
        List<Object> results = runConcurrently(List.of(
                () -> service.merge("alice",
                        new MergeRequest(key(), mergeKey(), "INC-A", "INC-B", 0L, 0L)),
                () -> service.merge("alice",
                        new MergeRequest(key(), mergeKey(), "INC-A", "INC-C", 0L, 0L))));

        assertThat(results.stream().filter(MergeView.class::isInstance).count()).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        assertThat(service.listMerges().merges()).hasSize(1);
        assertThat(service.get("INC-A").version()).isEqualTo(1L);
    }

    @Test
    void concurrentMerge_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        String commandKey = key();
        String mergeKey = mergeKey();
        List<Callable<MergeView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.merge("alice",
                    new MergeRequest(commandKey, mergeKey, "INC-A", "INC-B", 0L, 0L)));
        }
        List<Object> results = runConcurrently(tasks);

        List<MergeView> successes = results.stream().filter(MergeView.class::isInstance)
                .map(MergeView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，合并只生效一次
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(service.listMerges().merges()).hasSize(1);
        assertThat(service.get("INC-B").status()).isEqualTo("MERGED");
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentMergeAndTaskCreate_finalGraphAcyclic() throws Exception {
        commanding("INC-A", "alice");
        commanding("INC-B", "alice");
        commanding("INC-X", "bob");
        // 并发：合并 INC-B 入 INC-A，与在 INC-X 上创建以 INC-B 为阻塞的任务。
        // 提交顺序裁决：任务先提交则合并改挂该边指向 INC-A；合并先提交则
        // 任务创建因 MERGED 阻塞目标 409。两种结果最终图均无环、无指向 MERGED 的边。
        List<Object> results = runConcurrently(List.of(
                () -> service.merge("alice",
                        new MergeRequest(key(), mergeKey(), "INC-A", "INC-B", 0L, 0L)),
                () -> service.createTask("INC-X", "bob",
                        new TaskCreateRequest(key(), "TX-1", "G", "t", List.of("INC-B")))));

        assertThat(results.get(0)).isInstanceOf(MergeView.class);
        assertThat(service.get("INC-B").status()).isEqualTo("MERGED");
        Object createResult = results.get(1);
        if (createResult instanceof TaskView) {
            // 任务先提交：边被改挂到存续事件 INC-A
            TaskView task = service.getTask("INC-X", "TX-1");
            assertThat(task.blockers()).extracting("incidentKey").containsExactly("INC-A");
        } else {
            // 合并先提交：MERGED 不能作为新的阻塞目标，409 且不留任务
            assertThat(createResult).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            assertThat(service.listTasks("INC-X").tasks()).isEmpty();
        }
        // 最终图无指向 MERGED 事件的边、无指向自身的边
        Integer edgesToMerged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers b"
                        + " JOIN incidents i ON i.id = b.blocker_incident_id"
                        + " WHERE i.status = 'MERGED'", Integer.class);
        assertThat(edgesToMerged).isZero();
        Integer selfEdges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_task_blockers b"
                        + " JOIN incident_tasks t ON t.id = b.task_id"
                        + " WHERE t.incident_id = b.blocker_incident_id", Integer.class);
        assertThat(selfEdges).isZero();
    }
}
