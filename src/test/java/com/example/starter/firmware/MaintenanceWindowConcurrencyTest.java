package com.example.starter.firmware;

import com.example.starter.StarterApplication;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UpdateDeviceWindowRequest;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
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
 * 维护窗口并发裁决测试（真实 H2 事务与行锁，MODE=MySQL）：
 * 窗口外并发顺延不丢失不重复；窗口修订与拉取并发时按提交顺序裁决，同一任务不会既下发又顺延；
 * 暂停/恢复与窗口外拉取并发结果可区分；同 requestId 并发顺延只累加一次。
 */
@SpringBootTest(classes = {StarterApplication.class, ControllableClockConfig.class})
class MaintenanceWindowConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM task_defer_state");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        executor = Executors.newFixedThreadPool(8);
        ControllableClockConfig.setAt("2026-09-24T22:00:00Z");
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    private List<Object> runConcurrently(List<Callable<?>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<?> task : tasks) {
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

    private long setupWindowedRelease() {
        // 窗口本地 23:00~01:00；当前固定时刻 UTC 22:00 在窗口外
        deviceService.register(new RegisterDeviceRequest("req-d1", "d1", "m1", "1.0.0", 1,
                0, 1380, 60));
        return releaseService.create(new CreateReleaseRequest("req-r1", "m1", "1.0.0", "2.0.0",
                100, 2, 50, true)).releaseId();
    }

    @Test
    void 窗口外并发拉取_全部顺延_计数不丢失不重复且无任务() throws Exception {
        long releaseId = setupWindowedRelease();

        int threads = 8;
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-pull-" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.result()).isEqualTo(PullResponse.RESULT_DEFERRED);
            assertThat(response.task()).isNull();
            assertThat(response.nextWindowStartUtc()).isEqualTo("2026-09-24T23:00:00Z");
        }
        Long tasksCount = jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class);
        assertThat(tasksCount).isZero();
        Integer deferCount = jdbc.queryForObject(
                "SELECT defer_count FROM task_defer_state WHERE release_id = ? AND device_id = 'd1'",
                Integer.class, releaseId);
        assertThat(deferCount).isEqualTo(threads);
    }

    @Test
    void 窗口内并发拉取_至多一条任务_其余返回同一任务() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d1", "d1", "m1", "1.0.0", 1,
                0, 1380, 60));
        long releaseId = releaseService.create(new CreateReleaseRequest("req-r1", "m1", "1.0.0", "2.0.0",
                100, 2, 50, true)).releaseId();
        ControllableClockConfig.setAt("2026-09-24T23:30:00Z");

        int threads = 8;
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-pull-" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> taskIds = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.result()).isEqualTo(PullResponse.RESULT_DISPATCHED);
            assertThat(response.task()).isNotNull();
            taskIds.add(response.task().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = 'd1'",
                Long.class, releaseId);
        assertThat(count).isEqualTo(1);
        Long defers = jdbc.queryForObject("SELECT COUNT(*) FROM task_defer_state", Long.class);
        assertThat(defers).isZero();
    }

    @Test
    void 窗口修订与拉取并发_按提交顺序裁决_同一任务不会既下发又顺延() throws Exception {
        for (int round = 0; round < 12; round++) {
            String deviceId = "d" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, "m" + round,
                    "1.0.0", 1, 0, 1380, 60));
            long releaseId = releaseService.create(new CreateReleaseRequest("req-r" + round, "m" + round,
                    "1.0.0", "2.0.0", 100, 2, 50, true)).releaseId();
            final int seq = round;

            // 把窗口改为包含当前 UTC 22:00 的 [1200, 1380)（本地20:00~23:00），与拉取并发
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + seq),
                    (Callable<Object>) () -> deviceService.updateWindow(deviceId,
                            new UpdateDeviceWindowRequest("req-w" + seq, 1, 0, 1200, 1380)),
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-q" + seq)));

            // 无未知异常
            assertThat(results).noneMatch(r -> r instanceof Exception
                    && !(r instanceof com.example.starter.firmware.error.ApiException));

            long deferCount = jdbc.queryForObject(
                    "SELECT COALESCE((SELECT defer_count FROM task_defer_state WHERE release_id = ? AND device_id = ?),0)",
                    Long.class, releaseId, deviceId);
            long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                    Long.class, releaseId, deviceId);
            long deferredResponses = results.stream()
                    .filter(r -> r instanceof PullResponse p && p.result().equals(PullResponse.RESULT_DEFERRED))
                    .count();

            // 数据库顺延次数必须恰好等于 DEFERRED 响应数：不丢失、不重复累加
            assertThat(deferCount).as("第%d轮顺延次数与响应数一致", round).isEqualTo(deferredResponses);
            // 同一任务至多一条；任务一旦创建，后续拉取走“已存在任务”分支不可能再顺延，
            // 故有任务时 DEFERRED 只能来自其创建前已提交的顺延（提交顺序一致）
            assertThat(taskCount).isLessThanOrEqualTo(1);
            if (taskCount == 1) {
                long dispatched = results.stream()
                        .filter(r -> r instanceof PullResponse p
                                && p.result().equals(PullResponse.RESULT_DISPATCHED)).count();
                assertThat(dispatched + deferredResponses).isEqualTo(2);
            } else {
                // 无任务：两次拉取都只能是顺延
                assertThat(deferredResponses).isEqualTo(2);
            }
        }
    }

    @Test
    void 暂停与窗口外拉取并发_顺延与暂停理由互不混淆() throws Exception {
        // 两台目标设备：一台在窗口内先下发并回执造成暂停，与另一台窗口外拉取并发
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-a" + round, "a" + round, model,
                    "1.0.0", 1, 0, 0, 1439));
            deviceService.register(new RegisterDeviceRequest("req-b" + round, "b" + round, model,
                    "1.0.0", 2, 0, 0, 1439));
            long releaseId = releaseService.create(new CreateReleaseRequest("req-r" + round, model,
                    "1.0.0", "2.0.0", 100, 2, 50, true)).releaseId();

            // a：窗口内（UTC 00:00~23:59）下发后 FAILED；b 尚未拉取
            ControllableClockConfig.setAt("2026-09-24T12:00:00Z");
            long taskA = taskService.pull("a" + round, "req-pa" + round).task().taskId();
            // 第二次 FAILED 由本事务内的另一任务触发暂停：先准备 b 之外的第三台
            deviceService.register(new RegisterDeviceRequest("req-c" + round, "c" + round, model,
                    "1.0.0", 3, 0, 0, 1439));
            long taskC = taskService.pull("c" + round, "req-pc" + round).task().taskId();

            final int seq = round;
            // 并发：a FAILED 回执 + c FAILED 回执（触发暂停），同时 b 在窗口外（23:59 恰好右开边界外）拉取
            ControllableClockConfig.setAt("2026-09-24T23:59:00Z");
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.receipt(taskA,
                            new com.example.starter.firmware.api.ReceiptRequest(
                                    "req-ra" + seq, com.example.starter.firmware.domain.ReceiptResult.FAILED)),
                    (Callable<Object>) () -> taskService.receipt(taskC,
                            new com.example.starter.firmware.api.ReceiptRequest(
                                    "req-rc" + seq, com.example.starter.firmware.domain.ReceiptResult.FAILED)),
                    (Callable<Object>) () -> taskService.pull("b" + seq, "req-pb" + seq)));

            Object pullResult = results.get(2);
            assertThat(pullResult).isInstanceOf(PullResponse.class);
            PullResponse pullResponse = (PullResponse) pullResult;
            // b 永远在窗口外：无论暂停先提交还是后提交，都只能是 DEFERRED，不会被下发
            assertThat(pullResponse.result()).isEqualTo(PullResponse.RESULT_DEFERRED);
            assertThat(pullResponse.task()).isNull();
            Long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                    Long.class, releaseId, "b" + seq);
            assertThat(taskCount).isZero();
        }
    }

    @Test
    void 同requestId并发顺延_重放一致且只累加一次() throws Exception {
        long releaseId = setupWindowedRelease();

        int threads = 6;
        List<Callable<?>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.pull("d1", "req-same-defer"));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.result()).isEqualTo(PullResponse.RESULT_DEFERRED);
            assertThat(response.nextWindowStartUtc()).isEqualTo("2026-09-24T23:00:00Z");
        }
        Integer deferCount = jdbc.queryForObject(
                "SELECT defer_count FROM task_defer_state WHERE release_id = ? AND device_id = 'd1'",
                Integer.class, releaseId);
        assertThat(deferCount).isEqualTo(1);
        Long idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_id = 'req-same-defer'", Long.class);
        assertThat(idemCount).isEqualTo(1);
    }
}
