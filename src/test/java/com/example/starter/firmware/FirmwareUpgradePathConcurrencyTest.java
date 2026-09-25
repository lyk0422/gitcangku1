package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RegisterVersionRequest;
import com.example.starter.firmware.api.SkipLevelRequest;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.PathBlockedRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import com.example.starter.firmware.service.VersionService;
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
 * 升级路径并发边界测试：并发拉取拦截、跳级开关与拉取按提交顺序裁决、同键重放幂等。
 */
@SpringBootTest
class FirmwareUpgradePathConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private VersionService versionService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private PathBlockedRepository pathBlockedRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM path_blocked_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM firmware_version");
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

    private void registerChain123() {
        versionService.register(new RegisterVersionRequest("v1", "1.0", null));
        versionService.register(new RegisterVersionRequest("v2", "2.0", "1.0"));
        versionService.register(new RegisterVersionRequest("v3", "3.0", "2.0"));
    }

    @Test
    void 并发拉取_被拦截设备全部PATH_BLOCKED且不建任务() throws Exception {
        registerChain123();
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0", 1));
        long releaseId = releaseService.create(new CreateReleaseRequest("req-r", "m1", "1.0", "3.0", 100))
                .releaseId();

        int threads = 8;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-pull-" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.result()).isEqualTo("PATH_BLOCKED");
            assertThat(response.requiredVersion()).isEqualTo("2.0");
            assertThat(response.task()).isNull();
        }
        // 不建任务、不改设备版本；每个不同 requestId 的拦截各留一条历史
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isZero();
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("1.0");
        assertThat(pathBlockedRepository.countByRelease(releaseId)).isEqualTo(threads);
    }

    @Test
    void 并发开关修改_同expectedVersion只有一个成功() throws Exception {
        registerChain123();
        long releaseId = releaseService.create(new CreateReleaseRequest("req-r", "m1", "1.0", "3.0", 100))
                .releaseId();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> releaseService.setSkipLevel(releaseId,
                    new SkipLevelRequest("req-s" + seq, 1, true)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT)
                .count();
        // 行锁串行后按提交顺序裁决：第一个成功并将版本加一，其余版本冲突
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        var order = releaseService.findOrder(releaseId);
        assertThat(order.version()).isEqualTo(2);
        assertThat(order.allowSkip()).isTrue();
    }

    @Test
    void 并发同requestId开关修改_重放一致且版本只加一次() throws Exception {
        registerChain123();
        long releaseId = releaseService.create(new CreateReleaseRequest("req-r", "m1", "1.0", "3.0", 100))
                .releaseId();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> releaseService.setSkipLevel(releaseId,
                    new SkipLevelRequest("req-same", 1, true)));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        var order = releaseService.findOrder(releaseId);
        assertThat(order.version()).isEqualTo(2);
        assertThat(order.allowSkip()).isTrue();
    }

    @Test
    void 并发开关与拉取_按提交顺序产生一致结果() throws Exception {
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            String deviceId = "d" + round;
            String base = "1.0." + round;
            String mid = "2.0." + round;
            String target = "3.0." + round;
            versionService.register(new RegisterVersionRequest("v1-" + round, base, null));
            versionService.register(new RegisterVersionRequest("v2-" + round, mid, base));
            versionService.register(new RegisterVersionRequest("v3-" + round, target, mid));
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, base, 1));
            long releaseId = releaseService.create(
                    new CreateReleaseRequest("req-r" + round, model, base, target, 100)).releaseId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + seq),
                    (Callable<Object>) () -> releaseService.setSkipLevel(releaseId,
                            new SkipLevelRequest("req-s" + seq, 1, true))));

            assertThat(results.get(1)).as("开关修改对未终结发布单始终成功").isNotInstanceOf(Exception.class);
            Object pullResult = results.get(0);
            assertThat(pullResult).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) pullResult;
            long taskCount = taskRepository.countByReleaseAndDevice(releaseId, deviceId);
            long blockedCount = pathBlockedRepository.countByRelease(releaseId);
            if (response.result().equals("PATH_BLOCKED")) {
                // 拉取在开关提交前完成判定：拦截且不建任务
                assertThat(response.requiredVersion()).isEqualTo(mid);
                assertThat(taskCount).isZero();
                assertThat(blockedCount).isEqualTo(1);
            } else {
                // 开关先提交：忽略链校验直接下发
                assertThat(response.result()).isEqualTo("ISSUED");
                assertThat(response.task()).isNotNull();
                assertThat(taskCount).isEqualTo(1);
                assertThat(blockedCount).isZero();
            }
            assertThat(releaseService.findOrder(releaseId).allowSkip()).isTrue();
            assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.ACTIVE);
        }
    }
}
