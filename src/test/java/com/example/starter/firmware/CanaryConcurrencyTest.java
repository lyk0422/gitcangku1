package com.example.starter.firmware;

import com.example.starter.firmware.api.CanaryLevelRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PromoteRequest;
import com.example.starter.firmware.api.PromoteView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CanaryLevelRepository;
import com.example.starter.firmware.repo.CanaryPromotionRepository;
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
 * 金丝雀分级门禁并发测试：推进与回执、拉取并发时按事务提交顺序裁决；
 * 同 promoteKey 并发重放副作用只发生一次。
 */
@SpringBootTest
class CanaryConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private CanaryService canaryService;

    @Autowired
    private CanaryLevelRepository canaryLevelRepository;

    @Autowired
    private CanaryPromotionRepository canaryPromotionRepository;

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
    private <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
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

    private long createCanary(String requestId, String model, List<CanaryLevelRequest> levels) {
        return releaseService.create(new CreateReleaseRequest(requestId, model, "1.0.0", "2.0.0", null,
                levels)).releaseId();
    }

    @Test
    void 并发推进与回执_按提交顺序裁决且样本守恒() throws Exception {
        long releaseId = createCanary("req-r", "m1", List.of(
                new CanaryLevelRequest(50, 5, 100), new CanaryLevelRequest(100, 1, 100)));
        List<Long> taskIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String deviceId = "d" + i;
            deviceService.register(new RegisterDeviceRequest("req-d" + i, deviceId, "m1", "1.0.0", i));
            taskIds.add(taskService.pull(deviceId, "req-p" + i).task().taskId());
        }

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long taskId = taskIds.get(i);
            tasks.add(() -> taskService.receipt(taskId, new ReceiptRequest("req-rc" + taskId,
                    ReceiptResult.SUCCESS)));
        }
        tasks.add(() -> canaryService.promote(releaseId, new PromoteRequest("pk-race", null)));
        List<Object> results = runConcurrently(tasks);

        // 回执全部成功；推进要么在 5 个样本齐后成功，要么 422 样本不足
        for (int i = 0; i < 5; i++) {
            assertThat(results.get(i)).isNotInstanceOf(Exception.class);
        }
        Object promoteResult = results.get(5);
        var levels = canaryLevelRepository.findByRelease(releaseId);
        int totalSamples = levels.stream().mapToInt(l -> l.sampleCount()).sum();
        assertThat(totalSamples).isEqualTo(5);
        var promotions = canaryPromotionRepository.findByRelease(releaseId);
        if (promoteResult instanceof PromoteView view) {
            // 推进成功：当时第 1 级样本必然已齐 5 个，且只推进一次
            assertThat(view.currentLevel()).isEqualTo(2);
            assertThat(levels.get(0).sampleCount()).isEqualTo(5);
            assertThat(promotions).hasSize(1);
        } else {
            assertThat(promoteResult).isInstanceOfSatisfying(ApiException.class,
                    ae -> assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
            assertThat(promotions).isEmpty();
            assertThat(releaseService.findOrder(releaseId).currentLevel()).isEqualTo(1);
        }
    }

    @Test
    void 并发相同promoteKey_推进只生效一次() throws Exception {
        long releaseId = createCanary("req-r", "m1", List.of(
                new CanaryLevelRequest(50, 2, 100), new CanaryLevelRequest(100, 1, 100)));
        for (int i = 0; i < 2; i++) {
            String deviceId = "d" + i;
            deviceService.register(new RegisterDeviceRequest("req-d" + i, deviceId, "m1", "1.0.0", i));
            long taskId = taskService.pull(deviceId, "req-p" + i).task().taskId();
            taskService.receipt(taskId, new ReceiptRequest("req-rc" + i, ReceiptResult.SUCCESS));
        }

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> canaryService.promote(releaseId, new PromoteRequest("pk-same", null)));
        }
        List<Object> results = runConcurrently(tasks);

        PromoteView first = null;
        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
            PromoteView view = (PromoteView) result;
            assertThat(view.currentLevel()).isEqualTo(2);
            if (first == null) {
                first = view;
            } else {
                assertThat(view).isEqualTo(first);
            }
        }
        // 推进副作用只发生一次
        assertThat(releaseService.findOrder(releaseId).currentLevel()).isEqualTo(2);
        assertThat(canaryPromotionRepository.findByRelease(releaseId)).hasSize(1);
    }

    @Test
    void 并发推进与拉取_推进后新设备才能进入下一级别() throws Exception {
        long releaseId = createCanary("req-r", "m1", List.of(
                new CanaryLevelRequest(50, 1, 100), new CanaryLevelRequest(100, 1, 100)));
        deviceService.register(new RegisterDeviceRequest("req-d0", "d0", "m1", "1.0.0", 10));
        long taskId = taskService.pull("d0", "req-p0").task().taskId();
        taskService.receipt(taskId, new ReceiptRequest("req-rc0", ReceiptResult.SUCCESS));
        for (int i = 0; i < 8; i++) {
            deviceService.register(new RegisterDeviceRequest("req-e" + i, "e" + i, "m1", "1.0.0", 50 + i));
        }

        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> canaryService.promote(releaseId, new PromoteRequest("pk-promote", null)));
        for (int i = 0; i < 8; i++) {
            String deviceId = "e" + i;
            tasks.add(() -> taskService.pull(deviceId, "req-pull-" + deviceId));
        }
        List<Object> results = runConcurrently(tasks);

        // 推进成功，解锁第 2 级
        assertThat(results.get(0)).isNotInstanceOf(Exception.class);
        assertThat(releaseService.findOrder(releaseId).currentLevel()).isEqualTo(2);

        // 每个拉取要么在推进前提交（无任务），要么在推进后提交（获得任务），不得异常
        long tasksCreated = 0;
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i)).isNotInstanceOf(Exception.class);
            PullResponse response = (PullResponse) results.get(i);
            if (response.task() != null) {
                tasksCreated++;
            }
        }
        Long persisted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id LIKE 'e%'",
                Long.class, releaseId);
        assertThat(persisted).isEqualTo(tasksCreated);

        // 推进已提交后，全部设备重拉都能获得任务
        for (int i = 0; i < 8; i++) {
            PullResponse response = taskService.pull("e" + i, "req-repull-" + i);
            assertThat(response.task()).isNotNull();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId))
                .isEqualTo(9);
    }
}
