package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.DeclareHardwareRequest;
import com.example.starter.firmware.api.DispatchReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UpsertDeviceProfileRequest;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceProfileService;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.HardwareRequirementService;
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
 * 画像更新与任务创建竞争、画像乐观版本竞争、批量投放幂等重放的真实并发测试（H2 行锁与唯一约束）。
 */
@SpringBootTest
class FirmwareProfileConcurrencyTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private DeviceProfileService profileService;
    @Autowired
    private HardwareRequirementService requirementService;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task_profile_snapshot");
        jdbc.update("DELETE FROM rollout_exclusion");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_profile_tag");
        jdbc.update("DELETE FROM device_hardware_profile");
        jdbc.update("DELETE FROM device_profile_head");
        jdbc.update("DELETE FROM hardware_requirement_tag");
        jdbc.update("DELETE FROM hardware_requirement_model");
        jdbc.update("DELETE FROM hardware_requirement");
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

    private void seedCompatibleV1(String deviceId, String model) {
        deviceService.register(new RegisterDeviceRequest("req-dev-" + deviceId, deviceId, model, "1.0.0", 1));
        profileService.upsert(deviceId, new UpsertDeviceProfileRequest(
                "req-prof-init-" + deviceId, 0, model, 4096, List.of("BLE")));
    }

    @Test
    void 画像更新与拉取竞争_唯一任务只引用一个完整画像版本_快照一致() throws Exception {
        // 每轮使用独立型号，避免同型号未终结发布单唯一约束互相干扰；版本 V 声明全部轮次型号
        List<String> roundModels = new ArrayList<>();
        for (int round = 0; round < 12; round++) {
            roundModels.add("M" + round);
        }
        requirementService.declare(new DeclareHardwareRequest(
                "req-hw", "V", 4096, roundModels, List.of("BLE")));

        for (int round = 0; round < 12; round++) {
            String deviceId = "d" + round;
            String model = "M" + round;
            seedCompatibleV1(deviceId, model);
            long releaseId = releaseService.create(new CreateReleaseRequest(
                    "req-rel-" + round, model, "1.0.0", "V", 100)).releaseId();
            final int seq = round;

            List<Callable<Object>> tasks = new ArrayList<>();
            // 画像 v1 -> v2（内存 8192，多一个 NFC 标签），型号保持本轮型号不变
            tasks.add(() -> profileService.upsert(deviceId, new UpsertDeviceProfileRequest(
                    "req-prof-upd-" + seq, 1, model, 8192, List.of("BLE", "NFC"))));
            // 多个拉取竞争创建任务
            for (int i = 0; i < 4; i++) {
                int i2 = i;
                tasks.add(() -> taskService.pull(deviceId, "req-pull-" + seq + "-" + i2));
            }
            List<Object> results = runConcurrently(tasks);
            assertThat(results).as("第%d轮无意外异常", round)
                    .noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));

            // 恰好一个任务
            Long taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                    Long.class, releaseId, deviceId);
            assertThat(taskCount).as("第%d轮任务数", round).isEqualTo(1);

            // 任务引用的画像版本必须是完整存在的版本（1 或 2），且与冻结快照严格一致
            Integer usedVersion = jdbc.queryForObject(
                    "SELECT profile_version FROM rollout_task WHERE release_id = ? AND device_id = ?",
                    Integer.class, releaseId, deviceId);
            assertThat(usedVersion).isIn(1, 2);
            Integer snapshotVersion = jdbc.queryForObject(
                    "SELECT s.profile_version FROM rollout_task_profile_snapshot s"
                            + " JOIN rollout_task t ON t.id = s.task_id"
                            + " WHERE t.release_id = ? AND t.device_id = ?",
                    Integer.class, releaseId, deviceId);
            assertThat(snapshotVersion).isEqualTo(usedVersion);
            Integer snapshotMemory = jdbc.queryForObject(
                    "SELECT s.memory_mb FROM rollout_task_profile_snapshot s"
                            + " JOIN rollout_task t ON t.id = s.task_id"
                            + " WHERE t.release_id = ? AND t.device_id = ?",
                    Integer.class, releaseId, deviceId);
            assertThat(snapshotMemory).isEqualTo(usedVersion == 1 ? 4096 : 8192);

            // 画像最终为 v2，两个历史版本行都在且不可变
            assertThat(jdbc.queryForObject(
                    "SELECT current_version FROM device_profile_head WHERE device_id = ?",
                    Integer.class, deviceId)).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM device_hardware_profile WHERE device_id = ?",
                    Long.class, deviceId)).isEqualTo(2);
            // v1 快照未被 v2 追改
            assertThat(jdbc.queryForObject(
                    "SELECT memory_mb FROM device_hardware_profile WHERE device_id = ? AND profile_version = 1",
                    Integer.class, deviceId)).isEqualTo(4096);
        }
    }

    @Test
    void 并发同requestId批量投放_重放首次清单_任务只建一次() throws Exception {
        requirementService.declare(new DeclareHardwareRequest(
                "req-hw-dispatch", "V", 4096, List.of("M"), List.of("BLE")));
        String deviceId = "d1";
        seedCompatibleV1(deviceId, "M");
        // 再加一个不兼容设备（无 BLE）
        deviceService.register(new RegisterDeviceRequest("req-dev-d2", "d2", "M", "1.0.0", 2));
        profileService.upsert("d2", new UpsertDeviceProfileRequest(
                "req-prof-d2", 0, "M", 4096, List.of()));
        long releaseId = releaseService.create(new CreateReleaseRequest(
                "req-rel", "M", "1.0.0", "V", 100)).releaseId();

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> taskService.dispatch(releaseId, new DispatchReleaseRequest(
                    "req-dispatch-same", 1, List.of("d1", "d2"))));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isNotInstanceOf(Exception.class);
        }
        // 兼容设备只有一个任务；不兼容设备只有一条排除记录
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE release_id = ?",
                Long.class, releaseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_exclusion WHERE release_id = ?",
                Long.class, releaseId)).isEqualTo(1);
        // 全部重放返回相同的首次清单（1 建 1 排除）
        long createdOne = results.stream()
                .filter(r -> ((com.example.starter.firmware.api.DispatchResponse) r).created().size() == 1
                        && ((com.example.starter.firmware.api.DispatchResponse) r).excluded().size() == 1)
                .count();
        assertThat(createdOne).isEqualTo(threads);
    }

    @Test
    void 并发画像更新同基线版本_仅一个成功另一个409_历史版本不串行错乱() throws Exception {
        requirementService.declare(new DeclareHardwareRequest(
                "req-hw-upsert", "V", 4096, List.of("M"), List.of("BLE")));
        seedCompatibleV1("d1", "M");
        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> profileService.upsert("d1", new UpsertDeviceProfileRequest(
                "req-upd-a", 1, "M", 8192, List.of("BLE"))));
        tasks.add(() -> profileService.upsert("d1", new UpsertDeviceProfileRequest(
                "req-upd-b", 1, "M", 2048, List.of("BLE", "NFC"))));
        List<Object> results = runConcurrently(tasks);

        long success = results.stream().filter(r -> r instanceof com.example.starter.firmware.api.ProfileView)
                .count();
        long conflict = results.stream()
                .filter(r -> r instanceof ApiException ae
                        && ae.status() == HttpStatus.CONFLICT
                        && "PROFILE_VERSION_CONFLICT".equals(ae.code()))
                .count();
        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);
        // 只追加了一个 v2，head 指向 2
        assertThat(jdbc.queryForObject(
                "SELECT current_version FROM device_profile_head WHERE device_id = 'd1'",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_hardware_profile WHERE device_id = 'd1' AND profile_version = 2",
                Long.class)).isEqualTo(1);
    }

    @Test
    void 并发拉取_兼容设备至多一条任务且快照至多一份() throws Exception {
        requirementService.declare(new DeclareHardwareRequest(
                "req-hw-pull", "V", 4096, List.of("M"), List.of("BLE")));
        seedCompatibleV1("d1", "M");
        long releaseId = releaseService.create(new CreateReleaseRequest(
                "req-rel", "M", "1.0.0", "V", 100)).releaseId();

        int threads = 8;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-pull-" + seq));
        }
        List<Object> results = runConcurrently(tasks);
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            assertThat(((PullResponse) result).task()).isNotNull();
            assertThat(((PullResponse) result).task().profileVersion()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = 'd1'",
                Long.class, releaseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rollout_task_profile_snapshot s JOIN rollout_task t ON t.id = s.task_id"
                        + " WHERE t.release_id = ? AND t.device_id = 'd1'",
                Long.class, releaseId)).isEqualTo(1);
    }
}
