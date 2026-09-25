package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.StartReleaseRequest;
import com.example.starter.firmware.api.UpdateMatrixRequest;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.error.PrecheckException;
import com.example.starter.firmware.repo.IncompatibleRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.service.CompatService;
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
 * 兼容矩阵修改、拉取拦截、启动预检与回执并发时，按数据库行锁提交顺序裁决的真实并发测试（H2）。
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
    private CompatService compatService;
    @Autowired
    private ReleaseRepository releaseRepository;
    @Autowired
    private IncompatibleRecordRepository incompatibleRecordRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM device_incompatible_record");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM firmware_compat_matrix");
        jdbc.update("DELETE FROM hardware_model");
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

    private long prepareDraftWithDevices(String tag, int hwADevices, int hwBDevices, String toVersion) {
        String model = "model-" + tag;
        for (int i = 0; i < hwADevices; i++) {
            deviceService.register(new RegisterDeviceRequest("da" + tag + i, "da" + tag + i, model,
                    "HW-A", "1.0.0", i));
        }
        for (int i = 0; i < hwBDevices; i++) {
            deviceService.register(new RegisterDeviceRequest("db" + tag + i, "db" + tag + i, model,
                    "HW-B", "1.0.0", i + 50));
        }
        return releaseService.create(
                new CreateReleaseRequest("rr" + tag, model, "1.0.0", toVersion, 100, 2, 50)).releaseId();
    }

    @Test
    void 并发拉取_不兼容设备不产生任务_兼容设备同设备最多一条() throws Exception {
        long releaseId = prepareDraftWithDevices("T1", 4, 4, "2.0.0");
        // 仅兼容 B
        compatService.updateMatrix(new UpdateMatrixRequest("rm", "2.0.0", 0, List.of("HW-B")));
        releaseService.start(releaseId, new StartReleaseRequest("rs", 1));

        List<Callable<PullResponse>> tasks = new ArrayList<>();
        // 4 个 HW-A 各拉一次（拦截）；4 个 HW-B 中每个并发拉两次，验证唯一约束
        for (int i = 0; i < 4; i++) {
            int idx = i;
            tasks.add(() -> taskService.pull("daT1" + idx, "pa" + idx));
            tasks.add(() -> taskService.pull("dbT1" + idx, "pb" + idx + "-1"));
            tasks.add(() -> taskService.pull("dbT1" + idx, "pb" + idx + "-2"));
        }
        List<Object> results = runConcurrently(tasks);

        long incompatible = results.stream()
                .filter(r -> r instanceof PullResponse p && p.result().equals("INCOMPATIBLE"))
                .count();
        long tasksCreated = results.stream()
                .filter(r -> r instanceof PullResponse p && "TASK".equals(p.result()))
                .count();
        assertThat(incompatible).isEqualTo(4);
        // 每个 HW-B 的两次并发拉取都返回同一任务
        assertThat(tasksCreated).isEqualTo(8);

        Long totalTasks = jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class);
        assertThat(totalTasks).isEqualTo(4);
        Long blocked = jdbc.queryForObject("SELECT COUNT(*) FROM device_incompatible_record", Long.class);
        assertThat(blocked).isEqualTo(4);
        // 失败率统计保持 0：拦截不计样本
        assertThat(releaseService.findOrder(releaseId).roundFailed()).isZero();
    }

    @Test
    void 并发矩阵缩窄与拉取_任务矩阵版本恒为拉取时版本_HW_A永不会拿到版本2任务() throws Exception {
        long releaseId = prepareDraftWithDevices("T2", 6, 0, "2.0.0");
        // 仅登记 HW-B 为已知型号而不建设备：使初始矩阵可引用它，但候选设备全是 HW-A
        compatService.registerHardwareModel(
                new com.example.starter.firmware.api.RegisterHardwareModelRequest("rh-b2", "HW-B"));
        // 版本1：兼容 A 与 B
        compatService.updateMatrix(new UpdateMatrixRequest("rm1", "2.0.0", 0, List.of("HW-A", "HW-B")));
        releaseService.start(releaseId, new StartReleaseRequest("rs", 1));

        // 与「缩窄为仅 B」并发拉取 6 台 HW-A
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int idx = i;
            tasks.add(() -> taskService.pull("daT2" + idx, "p" + idx));
        }
        tasks.add(() -> compatService.updateMatrix(
                new UpdateMatrixRequest("rm2", "2.0.0", 1, List.of("HW-B"))));
        List<Object> results = runConcurrently(tasks);

        // 不变量：任何创建成功的 HW-A 任务都保存版本1；被缩窄拦截的落版本2记录
        List<Integer> taskMatrixVersions = jdbc.queryForList(
                "SELECT compat_matrix_version FROM rollout_task", Integer.class);
        assertThat(taskMatrixVersions).allMatch(v -> v != null && v == 1);
        for (Integer mv : jdbc.queryForList(
                "SELECT matrix_version FROM device_incompatible_record", Integer.class)) {
            assertThat(mv).isEqualTo(2);
        }
        // 任务数 + 拦截记录数 = 6（每台设备恰有一个结局）
        long taskCount = jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task", Long.class);
        long blockedCount = jdbc.queryForObject("SELECT COUNT(*) FROM device_incompatible_record", Long.class);
        assertThat(taskCount + blockedCount).isEqualTo(6);
        // 矩阵最终为版本2
        com.example.starter.firmware.api.MatrixView matrix = compatService.getMatrix("2.0.0");
        assertThat(matrix.version()).isEqualTo(2);
        assertThat(matrix.models()).containsExactly("HW-B");
        // 缩窄后再次拉取，此前未建任务的 HW-A 必被拦截
        for (int i = 0; i < 6; i++) {
            PullResponse response = taskService.pull("daT2" + i, "p-after-" + i);
            if (taskRepositoryHasTask(releaseId, "daT2" + i)) {
                assertThat(response.result()).isEqualTo("TASK");
                assertThat(response.task().compatMatrixVersion()).isEqualTo(1);
            } else {
                assertThat(response.result()).isEqualTo("INCOMPATIBLE");
            }
        }
        assertThat(results).noneMatch(r -> r instanceof Exception && !(r instanceof ApiException));
    }

    private boolean taskRepositoryHasTask(long releaseId, String deviceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM rollout_task WHERE release_id = ? AND device_id = ?",
                Long.class, releaseId, deviceId) > 0;
    }

    @Test
    void 并发启动与矩阵缩窄_每轮仅一种结局_全部不兼容时保持DRAFT() throws Exception {
        int startedBeforeNarrow = 0;
        int blockedByNarrow = 0;
        // 登记 HW-B 为已知型号但不建设备：候选设备全是 HW-A，缩窄到 B 即全部不兼容
        compatService.registerHardwareModel(
                new com.example.starter.firmware.api.RegisterHardwareModelRequest("rh-b-r", "HW-B"));
        for (int round = 0; round < 10; round++) {
            String tag = "R" + round;
            String toVersion = "2.0." + round;
            long releaseId = prepareDraftWithDevices(tag, 2, 0, toVersion);
            // 版本1 兼容 A/B，使启动有成功机会
            compatService.updateMatrix(new UpdateMatrixRequest("rm1-" + round, toVersion, 0,
                    List.of("HW-A", "HW-B")));
            final int seq = round;
            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> releaseService.start(releaseId,
                            new StartReleaseRequest("s" + seq, 1)),
                    (Callable<Object>) () -> compatService.updateMatrix(
                            new UpdateMatrixRequest("rm2-" + seq, toVersion, 1, List.of("HW-B")))));
            Object startResult = results.get(0);
            Object narrowResult = results.get(1);
            assertThat(narrowResult).as("矩阵缩窄自身始终成功提交").isNotInstanceOf(Exception.class);
            ReleaseStatus finalStatus = releaseService.findOrder(releaseId).status();
            if (startResult instanceof PrecheckException) {
                // 缩窄先提交：全部 HW-A 候选不兼容，启动 422，发布单仍 DRAFT
                blockedByNarrow++;
                assertThat(finalStatus).isEqualTo(ReleaseStatus.DRAFT);
                assertThat(((PrecheckException) startResult).models()).isNotEmpty();
            } else {
                // 启动先提交：发布单 ACTIVE；缩窄随后提交，只影响后续拉取——
                // 两个事务都结束后再拉取 HW-A，命中版本2矩阵被拦截，且不产生任务
                startedBeforeNarrow++;
                assertThat(startResult).isNotInstanceOf(Exception.class);
                assertThat(finalStatus).isEqualTo(ReleaseStatus.ACTIVE);
                PullResponse pull = taskService.pull("da" + tag + "0", "p" + seq);
                assertThat(pull.result()).isEqualTo("INCOMPATIBLE");
                assertThat(pull.task()).isNull();
            }
        }
        assertThat(startedBeforeNarrow + blockedByNarrow).isEqualTo(10);
    }

    @Test
    void 并发同requestId矩阵修改_重放一致且版本只升一次() throws Exception {
        prepareDraftWithDevices("T4", 1, 1, "2.0.0");
        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> compatService.updateMatrix(
                    new UpdateMatrixRequest("rm-same", "2.0.0", 0, List.of("HW-B"))));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);
        assertThat(compatService.getMatrix("2.0.0").version()).isEqualTo(1);

        // 不同 requestId 并发将版本1改成不同内容：行锁串行，两个变更都生效，版本到3
        List<Callable<Object>> changes = List.of(
                (Callable<Object>) () -> compatService.updateMatrix(
                        new UpdateMatrixRequest("rm-a", "2.0.0", 1, List.of("HW-A"))),
                (Callable<Object>) () -> compatService.updateMatrix(
                        new UpdateMatrixRequest("rm-b", "2.0.0", 1, List.of("HW-A", "HW-B"))));
        List<Object> changeResults = runConcurrently(changes);
        long conflicts = changeResults.stream()
                .filter(r -> r instanceof ApiException ae
                        && ae.status() == HttpStatus.CONFLICT
                        && "MATRIX_VERSION_CONFLICT".equals(ae.code()))
                .count();
        // 后提交者基于旧版本1更新，乐观条件失配 → 恰好 1 个 409，要求调用方重读版本重试
        assertThat(conflicts).isEqualTo(1);
        assertThat(compatService.getMatrix("2.0.0").version()).isEqualTo(2);
    }

    @Test
    void 部分不兼容设备失败回执_只统计兼容任务且可正常暂停() throws Exception {
        long releaseId = prepareDraftWithDevices("T5", 2, 2, "2.0.0");
        compatService.updateMatrix(new UpdateMatrixRequest("rm", "2.0.0", 0, List.of("HW-B")));
        releaseService.start(releaseId, new StartReleaseRequest("rs", 1));

        // 2 台 HW-A 被拦截
        assertThat(taskService.pull("daT50", "pa0").result()).isEqualTo("INCOMPATIBLE");
        assertThat(taskService.pull("daT51", "pa1").result()).isEqualTo("INCOMPATIBLE");
        // 2 台 HW-B 拿任务
        long t0 = taskService.pull("dbT50", "pb0").task().taskId();
        long t1 = taskService.pull("dbT51", "pb1").task().taskId();
        taskService.receipt(t0, new com.example.starter.firmware.api.ReceiptRequest(
                "rc0", com.example.starter.firmware.domain.ReceiptResult.FAILED));
        taskService.receipt(t1, new com.example.starter.firmware.api.ReceiptRequest(
                "rc1", com.example.starter.firmware.domain.ReceiptResult.FAILED));

        // floor=2、threshold=50：2 个兼容任务全失败即暂停；2 次拦截未稀释也未推高样本
        assertThat(releaseService.findOrder(releaseId).status()).isEqualTo(ReleaseStatus.PAUSED);
        assertThat(releaseService.findOrder(releaseId).roundFailed()).isEqualTo(2);
        assertThat(incompatibleRecordRepository.countByReleaseAndHardwareModel(releaseId, "HW-A")).isEqualTo(2);
        assertThat(incompatibleRecordRepository.countByReleaseAndHardwareModel(releaseId, "HW-B")).isZero();
    }
}
