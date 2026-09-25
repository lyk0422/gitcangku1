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
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.MergeRecordView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 重复事件合并并发边界测试：验证并发合并按事务提交顺序裁决（同一事件至多被合并一次、
 * mergeKey 全局唯一、同 commandKey 单次生效），以及合并与任务创建并发时
 * 依赖图全局锁保证最终图无环。
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
        jdbc.update("DELETE FROM incident_merge_tasks");
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

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S2", "并发场景", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    private static MergeRequest mergeRequest(String mergeKey, String survivingKey,
                                             String mergedKey) {
        return new MergeRequest(key(), mergeKey, survivingKey, mergedKey, 0L, 0L);
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
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        // 同一对事件并发合并（不同 mergeKey/commandKey）：行锁串行化，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.merge("alice", mergeRequest("MR-1", "INC-S", "INC-M")),
                () -> service.merge("alice", mergeRequest("MR-2", "INC-S", "INC-M"))));

        long successes = results.stream().filter(MergeRecordView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        // 同一事件最多被合并一次：仅一条合并记录，双方版本各加一
        assertThat(service.listMerges().merges()).hasSize(1);
        IncidentView merged = service.get("INC-M");
        assertThat(merged.status()).isEqualTo("MERGED");
        assertThat(merged.version()).isEqualTo(1);
        assertThat(service.get("INC-S").version()).isEqualTo(1);
    }

    @Test
    void concurrentMerge_sameMergedIncidentDifferentSurvivors_singleSuccess() throws Exception {
        commanding("INC-S1", "alice");
        commanding("INC-S2", "alice");
        commanding("INC-M", "alice");
        List<Object> results = runConcurrently(List.of(
                () -> service.merge("alice", mergeRequest("MR-1", "INC-S1", "INC-M")),
                () -> service.merge("alice", mergeRequest("MR-2", "INC-S2", "INC-M"))));

        long successes = results.stream().filter(MergeRecordView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        assertThat(service.listMerges().merges()).hasSize(1);
        assertThat(service.get("INC-M").status()).isEqualTo("MERGED");
        // 只有一个存续方版本递增
        long bumped = List.of(service.get("INC-S1"), service.get("INC-S2")).stream()
                .filter(v -> v.version() == 1).count();
        assertThat(bumped).isEqualTo(1);
    }

    @Test
    void concurrentMerge_sameMergeKey_singleRecord() throws Exception {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        // 相同 mergeKey 并发：唯一约束串行化，恰好一个成功
        List<Object> results = runConcurrently(List.of(
                () -> service.merge("alice", mergeRequest("MR-U", "INC-S", "INC-M")),
                () -> service.merge("alice", mergeRequest("MR-U", "INC-S", "INC-M"))));

        long successes = results.stream().filter(MergeRecordView.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        assertThat(service.listMerges().merges()).hasSize(1);
        Integer mergeRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_merges WHERE merge_key = 'MR-U'", Integer.class);
        assertThat(mergeRows).isEqualTo(1);
    }

    @Test
    void concurrentMerge_sameCommandKey_singleEffect() throws Exception {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        String commandKey = key();
        List<Callable<MergeRecordView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.merge("alice",
                    new MergeRequest(commandKey, "MR-C", "INC-S", "INC-M", 0L, 0L)));
        }
        List<Object> results = runConcurrently(tasks);

        List<MergeRecordView> successes = results.stream()
                .filter(MergeRecordView.class::isInstance)
                .map(MergeRecordView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，且合并只生效一次
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(service.listMerges().merges()).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class,
                commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentMergeAndReverseTaskCreate_finalGraphAcyclic() throws Exception {
        commanding("INC-S", "alice");
        commanding("INC-M", "alice");
        commanding("INC-X", "bob");
        // X → M 已存在：合并 M→S 后改指为 X→S
        service.createTask("INC-X", "bob",
                new TaskCreateRequest(key(), "T-X1", "G", "t", List.of("INC-M")));

        // 并发：合并 M→S 与在 S 上创建阻塞 X 的任务（S→X）——两者都成功会成环
        List<Object> results = runConcurrently(List.of(
                () -> service.merge("alice", mergeRequest("MR-G", "INC-S", "INC-M")),
                () -> service.createTask("INC-S", "alice",
                        new TaskCreateRequest(key(), "T-S1", "G", "t", List.of("INC-X")))));

        // 依赖图全局锁串行化：恰好一个成功，最终图无环
        long successes = results.stream()
                .filter(r -> !(r instanceof Exception)).count();
        assertThat(successes).isEqualTo(1);
        assertThat(countConflicts(results)).isEqualTo(1);
        Object mergeResult = results.get(0);
        if (mergeResult instanceof MergeRecordView) {
            // 合并先提交：X→S 边已改挂，S→X 创建被环检测拒绝
            assertThat(service.get("INC-M").status()).isEqualTo("MERGED");
            assertThat(service.getTask("INC-X", "T-X1").blockers())
                    .extracting(b -> b.incidentKey()).containsExactly("INC-S");
            assertThat(service.listTasks("INC-S").tasks()).isEmpty();
        } else {
            // 任务先创建：S→X 已在图中，合并改挂会成环被整体拒绝
            assertThat(service.get("INC-M").status()).isEqualTo("COMMANDING");
            assertThat(service.getTask("INC-S", "T-S1").blockers())
                    .extracting(b -> b.incidentKey()).containsExactly("INC-X");
            assertThat(service.listMerges().merges()).isEmpty();
        }
    }
}
