package com.example.starter.firmware;

import com.example.starter.firmware.api.ConfigureCompatRequest;
import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.FirmwareCompatView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.FirmwareCompatService;
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
 * 兼容矩阵并发与幂等边界测试：真实并发打到 H2 事务与唯一约束上，验证按提交顺序裁决。
 */
@SpringBootTest
class FirmwareCompatConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private FirmwareCompatService firmwareCompatService;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM incompatible_record");
        jdbc.update("DELETE FROM firmware_compat");
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

    @Test
    void 并发首次配置_按提交顺序仅一个成功_其余版本冲突() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "h1", "1.0.0", 1));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int seq = i;
            tasks.add(() -> firmwareCompatService.configure("2.0.0",
                    new ConfigureCompatRequest("req-c" + seq, 0, List.of("h1"))));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> r instanceof FirmwareCompatView).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT
                        && ae.code().equals("VERSION_CONFLICT"))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(3);
        assertThat(firmwareCompatService.get("2.0.0").matrixVersion()).isEqualTo(1);
    }

    @Test
    void 并发同requestId配置_重放一致且版本只加一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "h1", "1.0.0", 1));

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> firmwareCompatService.configure("2.0.0",
                    new ConfigureCompatRequest("req-same", 0, List.of("h1"))));
        }
        List<Object> results = runConcurrently(tasks);

        List<String> bodies = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(FirmwareCompatView.class);
            bodies.add(result.toString());
        }
        assertThat(bodies).allMatch(body -> body.equals(bodies.get(0)));
        assertThat(firmwareCompatService.get("2.0.0").matrixVersion()).isEqualTo(1);
    }

    @Test
    void 并发不兼容拉取_不创建任务且拦截记录只记首次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d1", "d1", "m1", "h1", "1.0.0", 1));
        deviceService.register(new RegisterDeviceRequest("req-d2", "d2", "m1", "h2", "1.0.0", 2));
        firmwareCompatService.configure("2.0.0", new ConfigureCompatRequest("req-c", 0, List.of("h2")));
        long releaseId = releaseService.create(
                new CreateReleaseRequest("req-r", "m1", "1.0.0", "2.0.0", 100)).releaseId();

        // 同设备并发拉取：部分线程同 requestId（重放），部分不同 requestId（重复执行）
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> taskService.pull("d1", "req-same"));
        }
        for (int i = 0; i < 4; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-p" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.result()).isEqualTo("INCOMPATIBLE");
            assertThat(response.task()).isNull();
        }
        // 不创建任务、不计样本、拦截记录仅一条
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM incompatible_record WHERE release_id = ? AND device_id = 'd1'",
                Long.class, releaseId)).isEqualTo(1);
        assertThat(releaseService.findOrder(releaseId).roundSuccess()).isZero();
        assertThat(releaseService.findOrder(releaseId).roundFailed()).isZero();
    }

    @Test
    void 并发矩阵缩窄与拉取_按提交顺序裁决且结果一致() throws Exception {
        int pullFirst = 0;
        int configFirst = 0;
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            String firmware = "2.0." + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, "d" + round, model, "h1",
                    "1.0.0", 1));
            deviceService.register(new RegisterDeviceRequest("req-e" + round, "e" + round, model, "h2",
                    "1.0.0", 2));
            firmwareCompatService.configure(firmware,
                    new ConfigureCompatRequest("req-c0-" + round, 0, List.of("h1", "h2")));
            long releaseId = releaseService.create(
                    new CreateReleaseRequest("req-r" + round, model, "1.0.0", firmware, 100)).releaseId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull("d" + seq, "req-p" + seq),
                    (Callable<Object>) () -> firmwareCompatService.configure(firmware,
                            new ConfigureCompatRequest("req-c1-" + seq, 1, List.of("h2")))));

            assertThat(results.get(0)).isInstanceOf(PullResponse.class);
            assertThat(results.get(1)).isInstanceOf(FirmwareCompatView.class);
            PullResponse pull = (PullResponse) results.get(0);
            Long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ?", Long.class, releaseId);
            Long incompatCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM incompatible_record WHERE release_id = ?", Long.class, releaseId);
            if (pull.result().equals("TASK")) {
                // 拉取先提交：看到旧矩阵 v1，任务固化矩阵版本 1
                pullFirst++;
                assertThat(taskCount).isEqualTo(1);
                assertThat(incompatCount).isZero();
                assertThat(pull.task().compatVersion()).isEqualTo(1);
            } else {
                // 矩阵缩窄先提交：拉取看到 v2，h1 被拦截
                configFirst++;
                assertThat(pull.result()).isEqualTo("INCOMPATIBLE");
                assertThat(taskCount).isZero();
                assertThat(incompatCount).isEqualTo(1);
            }
            // 矩阵最终版本为 2
            assertThat(firmwareCompatService.get(firmware).matrixVersion()).isEqualTo(2);
        }
        assertThat(pullFirst + configFirst).isEqualTo(10);
    }
}
