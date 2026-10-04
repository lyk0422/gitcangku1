package com.example.starter.observation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话水位并发边界测试：真实并发提交，验证同事务推进水位、
 * 同键并发只占一键、并发补洞与关闭串行化（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
class DeviceSessionConcurrencyTest {

    @Autowired
    private DeviceSessionService sessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM session_message");
        jdbcTemplate.update("DELETE FROM device_session");
        jdbcTemplate.update("DELETE FROM session_request_log");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<T>> synchronizedTasks = tasks.stream()
                .<Callable<T>>map(task -> () -> {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    return task.call();
                })
                .toList();
        try {
            List<Future<T>> futures = synchronizedTasks.stream().map(executor::submit).toList();
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers not ready in time");
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private SessionMessageRequest createMessage(String deviceId, String sessionId, int seq,
                                                String observationId, String note) {
        return new SessionMessageRequest(deviceId, sessionId, seq, SessionOperation.CREATE,
                observationId, null, "站点A", "1.0", note);
    }

    private BatchUploadRequest batch(String requestId, SessionMessageRequest... messages) {
        return new BatchUploadRequest(requestId, "op-1", List.of(messages));
    }

    private int countRows(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentGapFillAdvancesWatermarkAtomically() throws Exception {
        // 两个线程向同一会话并发补 seq 1 与 seq 2（会话尚不存在，并发新建由主键串行化）
        List<Callable<DeviceSessionService.BatchOutcome>> tasks = List.of(
                () -> sessionService.uploadBatch(batch("req-g1", createMessage("dev-1", "s-1", 1, "obs-a", "首条"))),
                () -> sessionService.uploadBatch(batch("req-g2", createMessage("dev-1", "s-1", 2, "obs-b", "次条"))));
        List<DeviceSessionService.BatchOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        SessionStateResponse state = sessionService.getSession("dev-1", "s-1");
        assertThat(state.watermark()).isEqualTo(2);
        assertThat(state.pending()).isEmpty();
        assertThat(state.messageCount()).isEqualTo(2);
        assertThat(countRows("SELECT COUNT(*) FROM session_message WHERE device_id = 'dev-1' AND session_id = 's-1'"))
                .isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdOccupiesSingleKey() throws Exception {
        // 同键同参并发：双方得到同一首次响应，去重记录与收件记录各占一行
        List<Callable<DeviceSessionService.BatchOutcome>> tasks = List.of(
                () -> sessionService.uploadBatch(batch("req-same", createMessage("dev-1", "s-1", 1, "obs-a", "首条"))),
                () -> sessionService.uploadBatch(batch("req-same", createMessage("dev-1", "s-1", 1, "obs-a", "首条"))));
        List<DeviceSessionService.BatchOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(outcomes.get(0).body()).isEqualTo(outcomes.get(1).body());
        assertThat(countRows("SELECT COUNT(*) FROM session_request_log WHERE request_id = 'req-same'")).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM session_message WHERE device_id = 'dev-1' AND session_id = 's-1'"))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-a'")).isEqualTo(1);
        assertThat(sessionService.getSession("dev-1", "s-1").watermark()).isEqualTo(1);
    }

    @Test
    void concurrentFillAndCloseAreSerialized() throws Exception {
        // 一个线程补 seq 1、2，另一个线程并发关闭（声明末序号 2）：行锁串行化，
        // 先补齐则关闭成功；先关闭则会话不存在 404 或水位不足 409；各种时序最终状态都必须自洽
        List<Callable<String>> tasks = List.of(
                () -> {
                    sessionService.uploadBatch(batch("req-f1",
                            createMessage("dev-1", "s-1", 1, "obs-a", "首条"),
                            createMessage("dev-1", "s-1", 2, "obs-b", "次条")));
                    return "fill-ok";
                },
                () -> {
                    try {
                        sessionService.closeSession("dev-1", "s-1",
                                new CloseSessionRequest("req-close", "op-1", 2));
                        return "close-ok";
                    } catch (ApiException e) {
                        return "close-" + e.status().value();
                    }
                });
        List<String> results = runConcurrently(tasks);

        assertThat(results).contains("fill-ok");
        SessionStateResponse state = sessionService.getSession("dev-1", "s-1");
        assertThat(state.watermark()).isEqualTo(2);
        if (results.contains("close-ok")) {
            assertThat(state.closed()).isTrue();
            assertThat(state.finalSeq()).isEqualTo(2);
        } else {
            assertThat(results).anyMatch(r -> r.equals("close-409") || r.equals("close-404"));
            assertThat(state.closed()).isFalse();
        }
        assertThat(countRows("SELECT COUNT(*) FROM session_message WHERE device_id = 'dev-1' AND session_id = 's-1'"))
                .isEqualTo(2);
    }

    @Test
    void concurrentBatchesOnDifferentSessionsDoNotInterfere() throws Exception {
        List<Callable<DeviceSessionService.BatchOutcome>> tasks = List.of(
                () -> sessionService.uploadBatch(batch("req-d1", createMessage("dev-1", "s-1", 1, "obs-a", "会话一"))),
                () -> sessionService.uploadBatch(batch("req-d2", createMessage("dev-1", "s-2", 1, "obs-b", "会话二"))),
                () -> sessionService.uploadBatch(batch("req-d3", createMessage("dev-2", "s-1", 1, "obs-c", "另一设备"))));
        List<DeviceSessionService.BatchOutcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(outcome -> outcome.status() == 200);
        assertThat(sessionService.getSession("dev-1", "s-1").watermark()).isEqualTo(1);
        assertThat(sessionService.getSession("dev-1", "s-2").watermark()).isEqualTo(1);
        assertThat(sessionService.getSession("dev-2", "s-1").watermark()).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM session_message")).isEqualTo(3);
    }
}