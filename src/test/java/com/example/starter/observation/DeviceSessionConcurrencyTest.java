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
 * 设备会话水位并发测试：真实并发提交，验证同事务推进水位、
 * 同键并发只执行一次、不同会话互不影响。
 */
@SpringBootTest
class DeviceSessionConcurrencyTest {

    @Autowired
    private DeviceSessionService deviceSessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM session_receipt");
        jdbcTemplate.update("DELETE FROM session_inbox");
        jdbcTemplate.update("DELETE FROM device_session");
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

    private UploadBatchRequest batch(String requestId, String deviceId, String sessionId, int... seqs) {
        List<SessionMessage> messages = new ArrayList<>();
        for (int seq : seqs) {
            String suffix = requestId + "-" + seq;
            messages.add(new SessionMessage(deviceId, sessionId, seq,
                    new ObservationCommand(CommandType.CREATE, "obs-req-" + suffix,
                            "obs-" + suffix, "站点" + seq, "1.0", "并发消息", null)));
        }
        return new UploadBatchRequest(requestId, "op-concurrent", messages);
    }

    private int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentGapFillAndConfirmAdvanceWatermarkAtomically() throws Exception {
        // 先收到 seq 1，水位 1
        deviceSessionService.uploadBatch(batch("req-c0", "dev-c", "s-c", 1));

        // 并发补洞（seq 2）与跳序确认（seq 3、4）：同会话在一个事务内推进水位
        List<Callable<BatchResponse>> tasks = List.of(
                () -> deviceSessionService.uploadBatch(batch("req-c1", "dev-c", "s-c", 2)),
                () -> deviceSessionService.uploadBatch(batch("req-c2", "dev-c", "s-c", 3, 4)));
        List<BatchResponse> responses = runConcurrently(tasks);

        assertThat(responses).hasSize(2);
        SessionStateView state = deviceSessionService.getSession("dev-c", "s-c");
        assertThat(state.watermark()).isEqualTo(4);
        assertThat(state.pendingSeqs()).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM session_inbox WHERE device_id = 'dev-c' AND session_id = 's-c'"))
                .isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM device_session WHERE device_id = 'dev-c' AND session_id = 's-c'"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM observation_version")).isEqualTo(4);
    }

    @Test
    void concurrentSameBatchRequestIdExecutesOnceAndReplays() throws Exception {
        UploadBatchRequest request = batch("req-dup", "dev-d", "s-d", 1, 2);
        List<Callable<BatchResponse>> tasks = List.of(
                () -> deviceSessionService.uploadBatch(request),
                () -> deviceSessionService.uploadBatch(request));
        List<BatchResponse> responses = runConcurrently(tasks);

        // 两个并发调用返回完全一致的首次响应
        assertThat(responses.get(0)).isEqualTo(responses.get(1));
        assertThat(responses.get(0).sessions().get(0).watermark()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM session_receipt WHERE request_id = 'req-dup'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM session_inbox WHERE device_id = 'dev-d' AND session_id = 's-d'"))
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM observation_version")).isEqualTo(2);
        assertThat(deviceSessionService.getSession("dev-d", "s-d").watermark()).isEqualTo(2);
    }

    @Test
    void concurrentDifferentSessionsWithSameSeqAreIndependent() throws Exception {
        List<Callable<BatchResponse>> tasks = List.of(
                () -> deviceSessionService.uploadBatch(batch("req-e1", "dev-e", "s-1", 1, 2)),
                () -> deviceSessionService.uploadBatch(batch("req-e2", "dev-e", "s-2", 1, 3)));
        runConcurrently(tasks);

        SessionStateView first = deviceSessionService.getSession("dev-e", "s-1");
        SessionStateView second = deviceSessionService.getSession("dev-e", "s-2");
        assertThat(first.watermark()).isEqualTo(2);
        assertThat(first.pendingSeqs()).isEmpty();
        assertThat(second.watermark()).isEqualTo(1);
        assertThat(second.pendingSeqs()).containsExactly(3);
        assertThat(count("SELECT COUNT(*) FROM device_session WHERE device_id = 'dev-e'")).isEqualTo(2);
    }
}