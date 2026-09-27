package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.DeviceModuleReportRequest;
import com.example.starter.firmware.api.ExemptionUpdateRequest;
import com.example.starter.firmware.api.MatrixRegisterRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.repo.BlockRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.service.DeviceModuleService;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ExemptionService;
import com.example.starter.firmware.service.MatrixService;
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
 * 模块兼容并发边界测试：真实并发打到 H2 事务与唯一约束上，
 * 验证矩阵登记、设备上报、豁免修改、拉取按提交顺序裁决，同一设备最多一个进行中任务。
 */
@SpringBootTest
class FirmwareModuleConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private ReleaseService releaseService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private MatrixService matrixService;

    @Autowired
    private DeviceModuleService deviceModuleService;

    @Autowired
    private ExemptionService exemptionService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private BlockRepository blockRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM module_block_item");
        jdbc.update("DELETE FROM module_block_record");
        jdbc.update("DELETE FROM release_exemption");
        jdbc.update("DELETE FROM device_module_report");
        jdbc.update("DELETE FROM device_module");
        jdbc.update("DELETE FROM compat_matrix_revision");
        jdbc.update("DELETE FROM firmware_requirement");
        jdbc.update("DELETE FROM module_dependency");
        jdbc.update("UPDATE compat_matrix_meta SET version = 0 WHERE id = 1");
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

    private long createRelease(String requestId, String model, String toVersion) {
        return releaseService.create(new CreateReleaseRequest(requestId, model, "1.0.0", toVersion,
                100)).releaseId();
    }

    @Test
    void 并发拉取_缺模块全部阻断_不建任务且每次阻断落记录() throws Exception {
        matrixService.register(new MatrixRegisterRequest("mx1",
                List.of(new MatrixRegisterRequest.RequirementEntry("2.0.0", "modem", "1.0")),
                List.of()));
        deviceService.register(new RegisterDeviceRequest("d1", "dev1", "m1", "1.0.0", 1, "g1"));
        long releaseId = createRelease("r1", "m1", "2.0.0");

        int threads = 8;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("dev1", "p" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.task()).isNull();
            assertThat(response.block()).isNotNull();
            assertThat(response.block().code()).isEqualTo("MODULE_BLOCKED");
            assertThat(response.block().missingModules()).hasSize(1);
        }
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "dev1")).isZero();
        assertThat(blockRepository.countByRelease(releaseId)).isEqualTo(threads);
        Long stats = jdbc.queryForObject(
                "SELECT round_success + round_failed FROM release_order WHERE id = ?",
                Long.class, releaseId);
        assertThat(stats).isZero();
    }

    @Test
    void 并发拉取_依赖满足_同设备最多一个进行中任务() throws Exception {
        matrixService.register(new MatrixRegisterRequest("mx1",
                List.of(new MatrixRegisterRequest.RequirementEntry("2.0.0", "modem", "1.0")),
                List.of()));
        deviceService.register(new RegisterDeviceRequest("d1", "dev1", "m1", "1.0.0", 1, "g1"));
        deviceModuleService.report("dev1", new DeviceModuleReportRequest("up1",
                List.of(new DeviceModuleReportRequest.ModuleEntry("modem", "1.5"))));
        long releaseId = createRelease("r1", "m1", "2.0.0");

        int threads = 8;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("dev1", "p" + seq));
        }
        List<Object> results = runConcurrently(tasks);

        List<Long> taskIds = new ArrayList<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.block()).isNull();
            assertThat(response.task()).isNotNull();
            taskIds.add(response.task().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
        assertThat(taskRepository.countByReleaseAndDevice(releaseId, "dev1")).isEqualTo(1);
        assertThat(blockRepository.countByRelease(releaseId)).isZero();
    }

    @Test
    void 并发同requestId上报_重放一致且只生效一次() throws Exception {
        deviceService.register(new RegisterDeviceRequest("d1", "dev1", "m1", "1.0.0", 1, "g1"));
        DeviceModuleReportRequest request = new DeviceModuleReportRequest("up-same",
                List.of(new DeviceModuleReportRequest.ModuleEntry("modem", "1.5")));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> deviceModuleService.report("dev1", request));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        assertThat(deviceModuleService.current("dev1").moduleReportVersion()).isEqualTo(1);
        assertThat(deviceModuleService.history("dev1").reports()).hasSize(1);
    }

    @Test
    void 并发异requestId上报_按提交顺序串行且版本唯一递增() throws Exception {
        deviceService.register(new RegisterDeviceRequest("d1", "dev1", "m1", "1.0.0", 1, "g1"));

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> deviceModuleService.report("dev1", new DeviceModuleReportRequest(
                    "up-" + seq,
                    List.of(new DeviceModuleReportRequest.ModuleEntry("modem", "1." + seq)))));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        // 设备行锁串行化：上报版本恰好覆盖 1..N，无重复无丢失
        assertThat(deviceModuleService.current("dev1").moduleReportVersion()).isEqualTo(threads);
        assertThat(deviceModuleService.history("dev1").reports())
                .extracting("reportVersion")
                .containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void 并发矩阵登记与拉取_按提交顺序裁决且快照一致() throws Exception {
        int taskCreated = 0;
        int blocked = 0;
        for (int round = 0; round < 10; round++) {
            String toVersion = "2.0." + round;
            String model = "m" + round;
            String deviceId = "d" + round;
            deviceService.register(new RegisterDeviceRequest("d" + round, deviceId, model,
                    "1.0.0", 1, "g1"));
            createRelease("r" + round, model, toVersion);
            int versionBefore = matrixService.currentMatrixVersion();
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> matrixService.register(new MatrixRegisterRequest(
                            "mx" + seq,
                            List.of(new MatrixRegisterRequest.RequirementEntry(toVersion, "modem",
                                    "1.0")),
                            List.of())),
                    (Callable<Object>) () -> taskService.pull(deviceId, "p" + seq)));

            assertThat(results.get(0)).isNotInstanceOf(Exception.class);
            assertThat(results.get(1)).isInstanceOf(PullResponse.class);
            int versionAfter = matrixService.currentMatrixVersion();
            assertThat(versionAfter).isEqualTo(versionBefore + 1);
            PullResponse pull = (PullResponse) results.get(1);
            if (pull.task() != null) {
                // 拉取先读到旧矩阵：无依赖要求，创建任务且快照为旧版本
                taskCreated++;
                assertThat(pull.block()).isNull();
                assertThat(pull.task().matrixVersion()).isEqualTo(versionBefore);
            } else {
                // 登记先提交：新矩阵要求 modem，设备未上报被阻断且快照为新版本
                blocked++;
                assertThat(pull.block()).isNotNull();
                assertThat(pull.block().matrixVersion()).isEqualTo(versionAfter);
            }
        }
        assertThat(taskCreated + blocked).isEqualTo(10);
    }

    @Test
    void 并发豁免修改与拉取_按提交顺序裁决且快照一致() throws Exception {
        int exempted = 0;
        int blocked = 0;
        for (int round = 0; round < 10; round++) {
            String toVersion = "2.0." + round;
            String model = "m" + round;
            String deviceId = "d" + round;
            matrixService.register(new MatrixRegisterRequest("mx" + round,
                    List.of(new MatrixRegisterRequest.RequirementEntry(toVersion, "modem", "1.0")),
                    List.of()));
            deviceService.register(new RegisterDeviceRequest("d" + round, deviceId, model,
                    "1.0.0", 1, "g1"));
            long releaseId = createRelease("r" + round, model, toVersion);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> exemptionService.replace(releaseId,
                            new ExemptionUpdateRequest("ex" + seq,
                                    List.of(new ExemptionUpdateRequest.ExemptionEntry("g1",
                                            List.of("modem"), "2099-01-01T00:00:00Z")))),
                    (Callable<Object>) () -> taskService.pull(deviceId, "p" + seq)));

            assertThat(results.get(0)).isNotInstanceOf(Exception.class);
            assertThat(results.get(1)).isInstanceOf(PullResponse.class);
            PullResponse pull = (PullResponse) results.get(1);
            if (pull.task() != null) {
                // 豁免先提交：设备组已豁免 modem，放行且快照豁免版本为1
                exempted++;
                assertThat(pull.task().exemptionVersion()).isEqualTo(1);
            } else {
                // 拉取先裁决：尚无豁免，阻断且快照豁免版本为0
                blocked++;
                assertThat(pull.block()).isNotNull();
                assertThat(pull.block().exemptionVersion()).isZero();
            }
        }
        assertThat(exempted + blocked).isEqualTo(10);
    }
}
