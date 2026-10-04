package com.example.starter.session;

import com.example.starter.observation.CreateObservationRequest;
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
 * 会话水位并发边界测试：真实并发提交，验证同事务内推进水位、
 * 同键并发重放只占一键、不同会话互不影响、并发首建同一会话不泄露唯一键异常。
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

    private BatchMessage createMessage(int seq, String requestId, String observationId, String note) {
        return new BatchMessage(seq, new CreateObservationCommand(
                new CreateObservationRequest(requestId, observationId, "站点", "1.0", note)));
    }

    private UploadBatchRequest batch(String requestId, String deviceId, String sessionId,
                                     BatchMessage... messages) {
        return new UploadBatchRequest(requestId, "tester", deviceId, sessionId, List.of(messages));
    }

    @Test
    void concurrentHoleFillingAdvancesWatermarkAtomically() throws Exception {
        sessionService.uploadBatch(batch("req-h0", "dev-1", "sess-c1",
                createMessage(1, "req-h1", "obs-h1", "首条")));

        // 并发补 2 与发 3：同会话在一个事务内推进水位，最终水位 3
        List<Callable<UploadBatchResponse>> tasks = List.of(
                () -> sessionService.uploadBatch(batch("req-h2b", "dev-1", "sess-c1",
                        createMessage(2, "req-h2", "obs-h2", "补洞"))),
                () -> sessionService.uploadBatch(batch("req-h3b", "dev-1", "sess-c1",
                        createMessage(3, "req-h3", "obs-h3", "跳序"))));
        List<UploadBatchResponse> responses = runConcurrently(tasks);

        assertThat(responses).hasSize(2);
        SessionStateResponse state = sessionService.getSession("dev-1", "sess-c1");
        assertThat(state.watermark()).isEqualTo(3);
        assertThat(state.receivedAboveWatermark()).isEmpty();
        Integer messages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_message WHERE device_id = 'dev-1' AND session_id = 'sess-c1'",
                Integer.class);
        assertThat(messages).isEqualTo(3);
    }

    @Test
    void concurrentFirstBatchesOnSameSessionCreateSingleSession() throws Exception {
        // 并发首建同一会话：主键串行化，两个批次都成功，水位连续推进到 2
        List<Callable<UploadBatchResponse>> tasks = List.of(
                () -> sessionService.uploadBatch(batch("req-n1b", "dev-2", "sess-c2",
                        createMessage(1, "req-n1", "obs-n1", "一"))),
                () -> sessionService.uploadBatch(batch("req-n2b", "dev-2", "sess-c2",
                        createMessage(2, "req-n2", "obs-n2", "二"))));
        List<UploadBatchResponse> responses = runConcurrently(tasks);

        assertThat(responses).hasSize(2);
        SessionStateResponse state = sessionService.getSession("dev-2", "sess-c2");
        assertThat(state.watermark()).isEqualTo(2);
        Integer sessions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_session WHERE device_id = 'dev-2' AND session_id = 'sess-c2'",
                Integer.class);
        assertThat(sessions).isEqualTo(1);
    }

    @Test
    void concurrentSameBatchRequestIdReplaysSingleResult() throws Exception {
        // 同一批次 requestId 并发提交 4 次：全部返回同一首次响应，去重只占一键
        List<Callable<UploadBatchResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> sessionService.uploadBatch(batch("req-dup", "dev-3", "sess-c3",
                    createMessage(1, "req-d1", "obs-d1", "并发重放"))));
        }
        List<UploadBatchResponse> responses = runConcurrently(tasks);

        assertThat(responses).allMatch(response -> response.watermark() == 1);
        assertThat(responses).allMatch(response -> response.receipts().size() == 1);
        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = 'req-dup'", Integer.class);
        assertThat(logs).isEqualTo(1);
        Integer messages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM session_message WHERE device_id = 'dev-3' AND session_id = 'sess-c3'",
                Integer.class);
        assertThat(messages).isEqualTo(1);
        Integer versions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-d1'", Integer.class);
        assertThat(versions).isEqualTo(1);
    }

    @Test
    void concurrentSameSeqOnDifferentSessionsAreIndependent() throws Exception {
        List<Callable<UploadBatchResponse>> tasks = List.of(
                () -> sessionService.uploadBatch(batch("req-s1b", "dev-4", "sess-a",
                        createMessage(1, "req-s1", "obs-s1", "会话A"))),
                () -> sessionService.uploadBatch(batch("req-s2b", "dev-4", "sess-b",
                        createMessage(1, "req-s2", "obs-s2", "会话B"))));
        List<UploadBatchResponse> responses = runConcurrently(tasks);

        assertThat(responses).allMatch(response -> response.watermark() == 1);
        assertThat(sessionService.getSession("dev-4", "sess-a").watermark()).isEqualTo(1);
        assertThat(sessionService.getSession("dev-4", "sess-b").watermark()).isEqualTo(1);
    }
}