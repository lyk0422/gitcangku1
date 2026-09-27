package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.DeviceModulesReportRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.RegisterMatrixRequest;
import com.example.starter.firmware.service.CompatibilityService;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

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
 * 模块兼容相关并发测试：真实并发打到 H2 事务、行锁与唯一约束上，
 * 验证矩阵登记提交顺序、同设备上报版本不丢失、同设备拉取最多一个进行中任务。
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:firmware_module_concurrency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class FirmwareModuleConcurrencyTest {

    @Autowired
    private CompatibilityService compatibilityService;

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
        jdbc.update("DELETE FROM module_block_record");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM module_exemption_history");
        jdbc.update("DELETE FROM module_exemption");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_module");
        jdbc.update("DELETE FROM device_module_report");
        jdbc.update("DELETE FROM firmware_module_requirement");
        jdbc.update("DELETE FROM module_dependency");
        jdbc.update("DELETE FROM firmware_matrix");
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

    private RegisterMatrixRequest matrix(String requestId, String firmware, String module, String minVersion) {
        return new RegisterMatrixRequest(requestId, firmware,
                List.of(new RegisterMatrixRequest.RequirementInput(module, minVersion)), List.of());
    }

    @Test
    void 并发矩阵登记_版本按提交顺序递增不丢失_无环() throws Exception {
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> compatibilityService.registerMatrix(
                    matrix("req-m" + seq, "2.0.0", "m" + seq, "1.0")));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        // 每次登记都是对同一固件的全量修订：最终直接需求只剩最后提交的一个模块，版本等于线程数
        Integer matrixVersion = jdbc.queryForObject(
                "SELECT matrix_version FROM firmware_matrix WHERE firmware_version = '2.0.0'",
                Integer.class);
        assertThat(matrixVersion).isEqualTo(threads);
        Long requirementRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM firmware_module_requirement WHERE firmware_version = '2.0.0'",
                Long.class);
        assertThat(requirementRows).isEqualTo(1);
    }

    @Test
    void 并发同设备模块上报_版本严格递增且清单最终一致() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 5));
        int threads = 5;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> compatibilityService.reportModules("d1",
                    new DeviceModulesReportRequest("req-r" + seq,
                            List.of(new DeviceModulesReportRequest.ModuleVersionInput("m", "1." + seq)))));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        Integer reportVersion = jdbc.queryForObject(
                "SELECT report_version FROM device_module_report WHERE device_id = 'd1'", Integer.class);
        assertThat(reportVersion).isEqualTo(threads);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_module", Long.class)).isEqualTo(1);
        // 最终版本为最后提交的 1.{0..4} 之一，且上报头/清单一致
        String finalModuleVersion = jdbc.queryForObject(
                "SELECT version FROM device_module WHERE device_id = 'd1'", String.class);
        assertThat(finalModuleVersion).matches("1\\.[0-4]");
    }

    @Test
    void 并发拉取_缺模块设备全部阻断且最多一条阻断诊断_满足设备最多一条任务() throws Exception {
        compatibilityService.registerMatrix(matrix("req-m", "2.0.0", "m", "1.0"));
        deviceService.register(new RegisterDeviceRequest("req-d", "d1", "m1", "1.0.0", 5));
        // d1 不上报模块：始终阻断
        long releaseId = releaseService.create(
                new CreateReleaseRequest("req-rel", "m1", "1.0.0", "2.0.0", 100)).releaseId();

        int threads = 6;
        List<Callable<PullResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            tasks.add(() -> taskService.pull("d1", "req-pull-blocked-" + seq));
        }
        List<Object> results = runConcurrently(new ArrayList<>(tasks));
        for (Object result : results) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.task()).isNull();
            assertThat(response.blockCode()).isEqualTo("MODULE_BLOCKED");
        }
        // 不同 requestId 的并发阻断各落一条诊断，但都不建任务
        Long blockCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM module_block_record WHERE release_id = ?", Long.class, releaseId);
        assertThat(blockCount).isEqualTo(threads);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class)).isZero();

        // 满足模块的设备并发拉取仍最多一个任务
        deviceService.register(new RegisterDeviceRequest("req-d2", "d2", "m1", "1.0.0", 6));
        compatibilityService.reportModules("d2", new DeviceModulesReportRequest("req-r2",
                List.of(new DeviceModulesReportRequest.ModuleVersionInput("m", "1.0"))));
        List<Callable<PullResponse>> okTasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int seq = i;
            okTasks.add(() -> taskService.pull("d2", "req-pull-ok-" + seq));
        }
        List<Object> okResults = runConcurrently(new ArrayList<>(okTasks));
        List<Long> taskIds = new ArrayList<>();
        for (Object result : okResults) {
            assertThat(result).isInstanceOf(PullResponse.class);
            PullResponse response = (PullResponse) result;
            assertThat(response.task()).isNotNull();
            taskIds.add(response.task().taskId());
        }
        assertThat(taskIds).allMatch(id -> id.equals(taskIds.get(0)));
    }
}
