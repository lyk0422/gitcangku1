package com.example.starter.firmware;

import com.example.starter.firmware.api.CanaryLevelRequest;
import com.example.starter.firmware.api.CanaryStatusView;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PromoteRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.CanaryService;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
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
 * 金丝雀推进的并发与幂等边界测试：真实并发打到 H2 事务与行锁上，验证按事务提交顺序裁决。
 */
@SpringBootTest
class FirmwareCanaryConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private CanaryService canaryService;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM canary_promotion");
        jdbc.update("DELETE FROM canary_level");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * 闩锁对齐后并发执行，收集成功结果与异常。
     */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(executor.submit(() -> {
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
    }

    private long createCanary(String requestId, String model, int level1Ratio, int minSamples,
                              int maxFailureRate, int level2Ratio) {
        return releaseService.create(new CreateReleaseRequest(requestId, model, "1.0.0", "2.0.0", 0,
                List.of(new CanaryLevelRequest(level1Ratio, minSamples, maxFailureRate),
                        new CanaryLevelRequest(level2Ratio, 1, 100)))).releaseId();
    }

    private long pullAndSucceed(String deviceId, String requestId) {
        long taskId = taskService.pull(deviceId, requestId).task().taskId();
        taskService.receipt(taskId, new ReceiptRequest(requestId + "-r", ReceiptResult.SUCCESS));
        return taskId;
    }

    @Test
    void 并发推进_不同promoteKey_仅一个成功另一个422() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d1", "d1", "m1", "1.0.0", 1));
        long releaseId = createCanary("req-r", "m1", 10, 1, 100, 20);
        pullAndSucceed("d1", "req-p1");

        List<Object> results = runConcurrently(List.of(
                () -> canaryService.promote(releaseId, new PromoteRequest("pk-a", 2)),
                () -> canaryService.promote(releaseId, new PromoteRequest("pk-b", 2))));

        long successes = results.stream().filter(r -> r instanceof CanaryStatusView).count();
        long skipRejected = results.stream()
                .filter(r -> r instanceof ApiException ae
                        && ae.status() == HttpStatus.UNPROCESSABLE_ENTITY
                        && ae.code().equals("PROMOTE_SKIP_LEVEL"))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(skipRejected).isEqualTo(1);
        assertThat(canaryService.status(releaseId).currentLevel()).isEqualTo(2);
        Long promotions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM canary_promotion WHERE release_id = ?", Long.class, releaseId);
        assertThat(promotions).isEqualTo(1);
    }

    @Test
    void 并发同promoteKey_重放一致且只推进一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d1", "d1", "m1", "1.0.0", 1));
        long releaseId = createCanary("req-r", "m1", 10, 1, 100, 20);
        pullAndSucceed("d1", "req-p1");

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> canaryService.promote(releaseId, new PromoteRequest("pk-same", 2)));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isInstanceOf(CanaryStatusView.class);
            assertThat(((CanaryStatusView) result).currentLevel()).isEqualTo(2);
        }
        assertThat(canaryService.status(releaseId).currentLevel()).isEqualTo(2);
        Long promotions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM canary_promotion WHERE release_id = ?", Long.class, releaseId);
        assertThat(promotions).isEqualTo(1);
    }

    @Test
    void 并发拉取与推进_按提交顺序裁决且最终状态一致() throws Exception {
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            String gateDevice = "g" + round;
            String raceDevice = "d" + round;
            // 门槛设备（桶1）先产生 1 个成功样本使第1级门禁通过
            deviceService.register(new RegisterDeviceRequest("req-g" + round, gateDevice, model, "1.0.0", 1));
            // 竞态设备桶号 15：第1级比例10不命中，推进到第2级比例20后命中
            deviceService.register(new RegisterDeviceRequest("req-d" + round, raceDevice, model, "1.0.0", 15));
            long releaseId = createCanary("req-r" + round, model, 10, 1, 100, 20);
            pullAndSucceed(gateDevice, "req-pg" + round);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    () -> taskService.pull(raceDevice, "req-pr" + seq),
                    () -> canaryService.promote(releaseId, new PromoteRequest("pk-" + seq, 2))));
            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));

            // 推进必然成功；拉取按提交顺序裁决：先于推进则无任务，后于推进则命中新级别
            CanaryStatusView status = canaryService.status(releaseId);
            assertThat(status.currentLevel()).isEqualTo(2);
            PullResponse pull = (PullResponse) results.get(0);
            Long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                    Long.class, releaseId, raceDevice);
            if (pull.task() == null) {
                assertThat(taskCount).as("第%d轮：拉取先于推进提交，不应有任务", round).isZero();
            } else {
                assertThat(taskCount).as("第%d轮：拉取后于推进提交，应恰好一条任务", round).isEqualTo(1);
                assertThat(pull.task().status()).isEqualTo("PENDING");
            }
        }
    }

    @Test
    void 并发回执_当前级别样本精确累计后推进成功() throws Exception {
        int devices = 8;
        for (int i = 0; i < devices; i++) {
            deviceService.register(new RegisterDeviceRequest("req-d" + i, "d" + i, "m1", "1.0.0", i + 1));
        }
        long releaseId = createCanary("req-r", "m1", 10, devices, 50, 20);
        List<Long> taskIds = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            taskIds.add(taskService.pull("d" + i, "req-p" + i).task().taskId());
        }

        // 8 个并发回执：6 成功 2 失败，样本与失败数须精确累计到当前级别
        List<Callable<Object>> receipts = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            long taskId = taskIds.get(i);
            ReceiptResult result = i < 2 ? ReceiptResult.FAILED : ReceiptResult.SUCCESS;
            receipts.add(() -> taskService.receipt(taskId, new ReceiptRequest("req-rr" + taskId, result)));
        }
        List<Object> results = runConcurrently(receipts);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        CanaryStatusView status = canaryService.status(releaseId);
        assertThat(status.levels().get(0).samples()).isEqualTo(devices);
        assertThat(status.levels().get(0).failures()).isEqualTo(2);

        // 失败率 25% 不超过上限 50%，样本数达标，推进成功
        CanaryStatusView promoted = canaryService.promote(releaseId, new PromoteRequest("pk-1", 2));
        assertThat(promoted.currentLevel()).isEqualTo(2);
        assertThat(promoted.effectiveRatio()).isEqualTo(20);
    }
}
