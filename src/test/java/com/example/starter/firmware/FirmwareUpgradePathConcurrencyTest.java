package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RegisterVersionRequest;
import com.example.starter.firmware.api.SetSkipRequest;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.PathBlockedRecordRepository;
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
 * 升级路径并发与幂等边界测试：真实并发打到 H2 事务与行锁上，
 * 验证 PATH_BLOCKED 判定、跳级开关与版本登记按提交顺序裁决。
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
    private PathBlockedRecordRepository pathBlockedRecordRepository;

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
        jdbc.update("DELETE FROM path_blocked_record");
        jdbc.update("DELETE FROM firmware_version");
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
        versionService.register(new RegisterVersionRequest("v-1", "1.0.0", null));
        versionService.register(new RegisterVersionRequest("v-2", "2.0.0", "1.0.0"));
        versionService.register(new RegisterVersionRequest("v-3", "3.0.0", "2.0.0"));
    }

    @Test
    void 并发拉取_PATH_BLOCKED判定一致且不产生任务不计样本() throws Exception {
        registerChain123();
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        long releaseId = releaseService.create(new CreateReleaseRequest("req-r", "m1", "2.0.0", "3.0.0", 100))
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
            assertThat(response.result()).isEqualTo(PullResponse.RESULT_PATH_BLOCKED);
            assertThat(response.nextVersion()).isEqualTo("2.0.0");
            assertThat(response.task()).isNull();
        }
        // 不产生任务、设备版本不变、不计入失败率样本；每次拉取落一条判定历史
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "d1")).isZero();
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("1.0.0");
        assertThat(pathBlockedRecordRepository.countByRelease(releaseId)).isEqualTo(threads);
        var order = releaseService.findOrder(releaseId);
        assertThat(order.roundSuccess()).isZero();
        assertThat(order.roundFailed()).isZero();
    }

    @Test
    void 并发同requestId拉取_重放一致且判定历史只落一条() throws Exception {
        registerChain123();
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        long releaseId = releaseService.create(new CreateReleaseRequest("req-r", "m1", "2.0.0", "3.0.0", 100))
                .releaseId();

        int threads = 6;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.pull("d1", "req-same"));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            assertThat(((PullResponse) result).result()).isEqualTo(PullResponse.RESULT_PATH_BLOCKED);
        }
        // 重放不重复执行：判定历史只落一条
        assertThat(pathBlockedRecordRepository.countByRelease(releaseId)).isEqualTo(1);
    }

    @Test
    void 并发拉取与跳级开关_按提交顺序裁决且任务至多一条() throws Exception {
        int blockedFirst = 0;
        int skipFirst = 0;
        for (int round = 0; round < 10; round++) {
            String model = "m" + round;
            String deviceId = "d" + round;
            versionService.register(new RegisterVersionRequest("v1-" + round, "1.0.0", null));
            versionService.register(new RegisterVersionRequest("v2-" + round, "2.0.0", "1.0.0"));
            versionService.register(new RegisterVersionRequest("v3-" + round, "3.0.0", "2.0.0"));
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, model, "1.0.0", 1));
            long releaseId = releaseService.create(
                    new CreateReleaseRequest("req-r" + round, model, "2.0.0", "3.0.0", 100)).releaseId();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> taskService.pull(deviceId, "req-p" + seq),
                    (Callable<Object>) () -> releaseService.setAllowSkip(releaseId,
                            new SetSkipRequest("req-s" + seq, 1, true))));
            assertThat(results).noneMatch(r -> r instanceof Exception);

            PullResponse pull = (PullResponse) results.get(0);
            long taskCount = taskRepository.countByReleaseAndDevice(releaseId, deviceId);
            if (pull.result().equals(PullResponse.RESULT_PATH_BLOCKED)) {
                // 拉取先提交：开关只影响后续拉取，本次仍被拦截
                blockedFirst++;
                assertThat(taskCount).isZero();
            } else {
                // 开关先提交：忽略前置链直接下发
                skipFirst++;
                assertThat(pull.result()).isEqualTo(PullResponse.RESULT_TASK);
                assertThat(taskCount).isEqualTo(1);
            }
            assertThat(releaseService.findOrder(releaseId).allowSkip()).isTrue();
            assertThat(releaseService.findOrder(releaseId).version()).isEqualTo(2);
        }
        assertThat(blockedFirst + skipFirst).isEqualTo(10);
    }

    @Test
    void 并发开关修改_相同expectedVersion仅一个成功() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 1));
        long releaseId = releaseService.create(new CreateReleaseRequest("req-r", "m1", "1.0.0", "2.0.0", 100))
                .releaseId();

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> releaseService.setAllowSkip(releaseId,
                        new SetSkipRequest("req-s1", 1, true)),
                (Callable<Object>) () -> releaseService.setAllowSkip(releaseId,
                        new SetSkipRequest("req-s2", 1, true))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae
                        && ae.status() == HttpStatus.CONFLICT && ae.code().equals("VERSION_CONFLICT"))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        var order = releaseService.findOrder(releaseId);
        assertThat(order.allowSkip()).isTrue();
        assertThat(order.version()).isEqualTo(2);
    }

    @Test
    void 并发同requestId版本登记_重放一致且只登记一次() throws Exception {
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> versionService.register(new RegisterVersionRequest("req-same", "1.0.0", null))
                    .version());
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isEqualTo("1.0.0");
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM firmware_version", Long.class);
        assertThat(count).isEqualTo(1);
    }
}
