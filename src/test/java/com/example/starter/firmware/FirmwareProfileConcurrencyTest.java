package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateFirmwareVersionRequest;
import com.example.starter.firmware.api.CreateLaunchRequest;
import com.example.starter.firmware.api.LaunchView;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UpsertDeviceProfileRequest;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.repo.ProfileExclusionRepository;
import com.example.starter.firmware.repo.ProfileTaskRepository;
import com.example.starter.firmware.service.DeviceProfileService;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.FirmwareVersionService;
import com.example.starter.firmware.service.ProfileLaunchService;
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
 * 画像投放并发与幂等边界测试：真实并发打到 H2 事务与唯一约束上。
 */
@SpringBootTest
class FirmwareProfileConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private DeviceProfileService profileService;

    @Autowired
    private FirmwareVersionService versionService;

    @Autowired
    private ProfileLaunchService launchService;

    @Autowired
    private ProfileTaskRepository taskRepository;

    @Autowired
    private ProfileExclusionRepository exclusionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM profile_task");
        jdbc.update("DELETE FROM profile_exclusion");
        jdbc.update("DELETE FROM profile_launch");
        jdbc.update("DELETE FROM device_profile");
        jdbc.update("DELETE FROM firmware_version");
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

    private long createVersion(String requestId, String name) {
        return versionService.create(new CreateFirmwareVersionRequest(requestId, name,
                List.of("M"), 4096, List.of("BLE"))).versionId();
    }

    @Test
    void 并发同requestId投放_只执行一次且重放首次目标清单() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d1", "d1", "m1", "1.0.0", 1));
        deviceService.register(new RegisterDeviceRequest("req-d2", "d2", "m1", "1.0.0", 2));
        profileService.upsert("d1", new UpsertDeviceProfileRequest("req-p1", "M", 4096, List.of("BLE")));
        profileService.upsert("d2", new UpsertDeviceProfileRequest("req-p2", "M", 4096, List.of()));
        long versionId = createVersion("req-v", "V");

        int threads = 6;
        List<Callable<LaunchView>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> launchService.launch(new CreateLaunchRequest("req-same", versionId)));
        }
        List<Object> results = runConcurrently(tasks);

        Long firstLaunchId = null;
        for (Object result : results) {
            assertThat(result).isInstanceOf(LaunchView.class);
            LaunchView view = (LaunchView) result;
            if (firstLaunchId == null) {
                firstLaunchId = view.launchId();
            }
            // 重放首次目标清单：投放单、任务与排除清单完全一致
            assertThat(view.launchId()).isEqualTo(firstLaunchId);
            assertThat(view.tasks()).hasSize(1);
            assertThat(view.exclusions()).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile_launch", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile_task", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM profile_exclusion", Long.class)).isEqualTo(1);
    }

    @Test
    void 并发画像更新与投放_每个任务只引用一个完整画像版本() throws Exception {
        int taskCreated = 0;
        int excluded = 0;
        for (int round = 0; round < 10; round++) {
            String deviceId = "d" + round;
            deviceService.register(new RegisterDeviceRequest("req-d" + round, deviceId, "m1", "1.0.0", 1));
            profileService.upsert(deviceId,
                    new UpsertDeviceProfileRequest("req-p" + round, "M", 4096, List.of("BLE")));
            long versionId = createVersion("req-v" + round, "V" + round);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> launchService.launch(
                            new CreateLaunchRequest("req-l" + seq, versionId)),
                    (Callable<Object>) () -> profileService.upsert(deviceId,
                            new UpsertDeviceProfileRequest("req-u" + seq, "M", 4096, List.of()))));
            assertThat(results).noneMatch(r -> r instanceof Exception);

            long launchId = jdbc.queryForObject("SELECT id FROM profile_launch", Long.class);
            // 只看本轮设备：历史轮次设备也会被评估，与本轮断言无关
            var tasksOfLaunch = taskRepository.findByLaunch(launchId).stream()
                    .filter(t -> t.deviceId().equals(deviceId)).toList();
            var exclusions = exclusionRepository.findByLaunch(launchId).stream()
                    .filter(e -> e.deviceId().equals(deviceId)).toList();
            // 同一设备要么建任务要么被排除，二者居其一
            assertThat(tasksOfLaunch.size() + exclusions.size()).isEqualTo(1);
            if (!tasksOfLaunch.isEmpty()) {
                // 任务引用画像版本1（含BLE的完整画像），不可能引用不兼容的版本2
                taskCreated++;
                assertThat(tasksOfLaunch.get(0).profileVersion()).isEqualTo(1);
            } else {
                // 排除记录引用画像版本2（去掉BLE后的完整画像）
                excluded++;
                assertThat(exclusions.get(0).profileVersion()).isEqualTo(2);
                assertThat(exclusions.get(0).reason()).isEqualTo("缺少必需标签: BLE");
            }
            jdbc.update("DELETE FROM profile_task");
            jdbc.update("DELETE FROM profile_exclusion");
            jdbc.update("DELETE FROM profile_launch");
        }
        assertThat(taskCreated + excluded).isEqualTo(10);
    }

    @Test
    void 并发回执_统计不丢失且失败率只统计任务() throws Exception {
        int devices = 8;
        for (int i = 0; i < devices; i++) {
            deviceService.register(new RegisterDeviceRequest("req-d" + i, "d" + i, "m1", "1.0.0", i));
            profileService.upsert("d" + i,
                    new UpsertDeviceProfileRequest("req-p" + i, "M", 4096, List.of("BLE")));
        }
        // 一台不兼容设备：不计入失败率
        deviceService.register(new RegisterDeviceRequest("req-dx", "dx", "m1", "1.0.0", 9));
        profileService.upsert("dx", new UpsertDeviceProfileRequest("req-px", "M", 1024, List.of()));
        long versionId = createVersion("req-v", "V");
        LaunchView launch = launchService.launch(new CreateLaunchRequest("req-l", versionId));
        assertThat(launch.tasks()).hasSize(devices);
        assertThat(launch.exclusions()).hasSize(1);

        List<Callable<Object>> receipts = new ArrayList<>();
        for (var task : launch.tasks()) {
            receipts.add(() -> launchService.receipt(task.taskId(),
                    new ReceiptRequest("req-r" + task.taskId(), ReceiptResult.FAILED)));
        }
        List<Object> results = runConcurrently(receipts);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        LaunchView view = launchService.getLaunch(launch.launchId());
        assertThat(view.stats().taskCount()).isEqualTo(devices);
        assertThat(view.stats().failedCount()).isEqualTo(devices);
        assertThat(view.stats().failureRatePercent()).isEqualTo(100);
        assertThat(view.stats().excludedCount()).isEqualTo(1);
    }
}
