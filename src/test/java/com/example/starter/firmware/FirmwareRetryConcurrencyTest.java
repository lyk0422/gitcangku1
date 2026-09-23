package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RetryTaskRequest;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.TaskRepository;
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
 * 失败任务显式重试的并发与幂等边界测试：真实并发打到 H2 事务、行锁与唯一约束上。
 */
@SpringBootTest
class FirmwareRetryConcurrencyTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
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

    private long failedTask(long releaseId, String deviceId) {
        long taskId = taskService.pull(deviceId, "pull-" + deviceId).task().taskId();
        taskService.receipt(taskId, new ReceiptRequest("fail-" + deviceId,
                com.example.starter.firmware.domain.ReceiptResult.FAILED));
        return taskId;
    }

    @Test
    void 两个重试请求竞争_只能创建一个后继() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("req-r", "m1", "1.0.0", "2.0.0", 100, 100, 100)).releaseId();
        long t1 = failedTask(releaseId, "d1");

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.retry(t1, new RetryTaskRequest("retry-" + seq, 1)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream()
                .filter(r -> !(r instanceof Exception))
                .count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        // 每个冲突都应指向“已有后继/不可重试”，且最终只有一个序号2任务
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isEqualTo(2);
        Long attempt2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = 'd1' AND attempt_no = 2",
                Long.class, releaseId);
        assertThat(attempt2).isEqualTo(1);
        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = 'd1' AND status = 'PENDING'",
                Long.class, releaseId);
        assertThat(pending).isEqualTo(1);
    }

    @Test
    void 并发同requestId重试_重放一致且只创建一个后继() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("req-r", "m1", "1.0.0", "2.0.0", 100, 100, 100)).releaseId();
        long t1 = failedTask(releaseId, "d1");

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.retry(t1, new RetryTaskRequest("same-req", 1)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        @SuppressWarnings("unchecked")
        List<Long> newTaskIds = results.stream()
                .map(r -> ((com.example.starter.firmware.api.TaskView) r).taskId())
                .distinct()
                .toList();
        assertThat(newTaskIds).hasSize(1);
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isEqualTo(2);
    }

    @Test
    void 并发重试与取消_取消后不留PENDING() throws Exception {
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            String model = "m" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 1));
            long releaseId = releaseService.create(new CreateReleaseRequest(
                    "req-r" + round, model, "1.0.0", "2.0.0", 100, 100, 100)).releaseId();
            long t1 = failedTask(releaseId, deviceId);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.retry(t1, new RetryTaskRequest("rt" + seq, 1)),
                    (Callable<Object>) () -> releaseService.cancel(releaseId, "cx" + seq)));

            assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));
            assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.CANCELLED);
            Long pending = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND status = 'PENDING'",
                    Long.class, releaseId);
            assertThat(pending).as("取消后不得残留 PENDING 尝试（第%d轮）", round).isZero();
        }
    }

    @Test
    void 并发重试与成功回执新尝试_前驱失败样本保留且设备仅在成功时升级() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("req-r", "m1", "1.0.0", "2.0.0", 100, 100, 100)).releaseId();
        long t1 = failedTask(releaseId, "d1");

        // 先串行拿到唯一后继，再让“对旧任务的二次重试”与“新尝试成功回执”并发
        long t2 = taskService.retry(t1, new RetryTaskRequest("rt", 1)).taskId();
        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> taskService.retry(t1, new RetryTaskRequest("rt-old", 1)),
                (Callable<Object>) () -> taskService.receipt(t2,
                        new ReceiptRequest("ok", com.example.starter.firmware.domain.ReceiptResult.SUCCESS))));

        // 对旧任务重试必为 409（非最新尝试），成功回执必成功
        assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                ae -> assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(results.get(1)).isNotInstanceOf(Exception.class);
        assertThat(taskRepository.findById(t1).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
        assertThat(taskRepository.findById(t2).orElseThrow().status()).isEqualTo(TaskStatus.SUCCESS);
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("2.0.0");
        // 样本：原失败 1 + 新成功 1
        assertThat(releaseService.monitor(releaseId).roundFailed()).isEqualTo(1);
        assertThat(releaseService.monitor(releaseId).roundSuccess()).isEqualTo(1);
    }
}
