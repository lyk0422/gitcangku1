package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateGroupReleaseRequest;
import com.example.starter.firmware.api.GroupActivateRequest;
import com.example.starter.firmware.api.GroupPrepareRequest;
import com.example.starter.firmware.api.GroupReleaseView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.PrepareResult;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.GroupReleaseService;
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
 * 组预备屏障并发测试：真实并发打到 H2 事务与唯一约束上，
 * 验证准备、取消与激活竞争只产生一个完整终态，不出现部分设备切版本。
 */
@SpringBootTest
class FirmwareGroupConcurrencyTest {

    @Autowired
    private GroupReleaseService groupService;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM group_member");
        jdbc.update("DELETE FROM group_release");
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

    private long newGroup(String tag, String model, String... deviceIds) {
        for (String deviceId : deviceIds) {
            deviceService.register(new RegisterDeviceRequest("rd-" + deviceId + tag, deviceId, model, "v1", 1));
        }
        return groupService.create(new CreateGroupReleaseRequest("gc-" + tag, "op", model, "v2",
                List.of(deviceIds))).groupId();
    }

    private void prepare(long groupId, String deviceId, String requestId) {
        groupService.prepare(groupId, deviceId,
                new GroupPrepareRequest(requestId, "op", 1, PrepareResult.PREPARED));
    }

    @Test
    void 并发激活与取消_唯一完整终态_不出现部分切版本() throws Exception {
        int activated = 0;
        int cancelled = 0;
        for (int round = 0; round < 8; round++) {
            String a = "A" + round;
            String b = "B" + round;
            long gid = newGroup("r" + round, "m" + round, a, b);
            prepare(gid, a, "pa-" + round);
            prepare(gid, b, "pb-" + round);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> groupService.activate(gid,
                            new GroupActivateRequest("act-" + seq, "op", 1)),
                    (Callable<Object>) () -> groupService.cancel(gid, "can-" + seq, "op")));

            GroupReleaseView view = groupService.get(gid);
            String versionA = deviceService.get(a).currentVersion();
            String versionB = deviceService.get(b).currentVersion();
            if (view.status().equals("ACTIVATED")) {
                activated++;
                assertThat(results.get(0)).isNotInstanceOf(Exception.class);
                assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(versionA).isEqualTo("v2");
                assertThat(versionB).isEqualTo("v2");
                assertThat(view.successCount()).isEqualTo(2);
                assertThat(view.activationGeneration()).isEqualTo(1);
            } else {
                cancelled++;
                assertThat(view.status()).isEqualTo("CANCELLED");
                assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
                assertThat(results.get(1)).isNotInstanceOf(Exception.class);
                assertThat(versionA).isEqualTo("v1");
                assertThat(versionB).isEqualTo("v1");
            }
            // 终态后占用已释放：同设备可建新的组
            assertThat(groupService.create(new CreateGroupReleaseRequest("gc2-" + seq, "op", "m" + round,
                    "v3", List.of(a, b))).status()).isEqualTo("PREPARING");
        }
        assertThat(activated + cancelled).isEqualTo(8);
    }

    @Test
    void 并发准备失败与取消_唯一终态且释放占用() throws Exception {
        int aborted = 0;
        int cancelled = 0;
        for (int round = 0; round < 8; round++) {
            String a = "A" + round;
            String b = "B" + round;
            long gid = newGroup("r" + round, "m" + round, a, b);
            prepare(gid, a, "pa-" + round);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> groupService.prepare(gid, b,
                            new GroupPrepareRequest("pf-" + seq, "op", 1, PrepareResult.FAILED)),
                    (Callable<Object>) () -> groupService.cancel(gid, "can-" + seq, "op")));

            GroupReleaseView view = groupService.get(gid);
            if (view.status().equals("ABORTED")) {
                aborted++;
                assertThat(view.failedDevice()).isEqualTo(b);
                assertThat(view.failedCount()).isEqualTo(1);
            } else {
                cancelled++;
                assertThat(view.status()).isEqualTo("CANCELLED");
                assertThat(view.failedCount()).isZero();
                assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
            }
            // 任一终态都不切版本，且占用释放可再建组
            assertThat(deviceService.get(a).currentVersion()).isEqualTo("v1");
            assertThat(deviceService.get(b).currentVersion()).isEqualTo("v1");
            Long active = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM group_member WHERE active_device IS NOT NULL", Long.class);
            assertThat(active).isZero();
        }
        assertThat(aborted + cancelled).isEqualTo(8);
    }

    @Test
    void 并发同requestId激活_重放一致且副作用只一次() throws Exception {
        long gid = newGroup("same", "m1", "A", "B");
        prepare(gid, "A", "pa");
        prepare(gid, "B", "pb");

        int threads = 6;
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> groupService.activate(gid, new GroupActivateRequest("act-same", "op", 1)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);
        GroupReleaseView first = (GroupReleaseView) results.get(0);
        for (Object result : results) {
            assertThat(result).isEqualTo(first);
        }
        GroupReleaseView view = groupService.get(gid);
        assertThat(view.status()).isEqualTo("ACTIVATED");
        assertThat(view.successCount()).isEqualTo(2);
        assertThat(view.activationGeneration()).isEqualTo(1);
        assertThat(deviceService.get("A").currentVersion()).isEqualTo("v2");
        assertThat(deviceService.get("B").currentVersion()).isEqualTo("v2");
    }

    @Test
    void 并发创建同设备组_唯一约束兜底不泄露() throws Exception {
        deviceService.register(new RegisterDeviceRequest("rd-A", "A", "m1", "v1", 1));
        deviceService.register(new RegisterDeviceRequest("rd-B", "B", "m1", "v1", 1));

        List<Object> results = runConcurrently(List.of(
                (Callable<Object>) () -> groupService.create(
                        new CreateGroupReleaseRequest("gc-1", "op", "m1", "v2", List.of("A", "B"))),
                (Callable<Object>) () -> groupService.create(
                        new CreateGroupReleaseRequest("gc-2", "op", "m1", "v2", List.of("A", "B")))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && ae.status() == HttpStatus.CONFLICT
                        && ae.code().equals("DEVICE_IN_ACTIVE_GROUP"))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_release", Long.class)).isEqualTo(1);
    }

    @Test
    void 并发最后准备与激活_要么422要么全组切换_不产生部分切换() throws Exception {
        for (int round = 0; round < 8; round++) {
            String a = "A" + round;
            String b = "B" + round;
            long gid = newGroup("r" + round, "m" + round, a, b);
            prepare(gid, a, "pa-" + round);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    (Callable<Object>) () -> groupService.prepare(gid, b,
                            new GroupPrepareRequest("pb-" + seq, "op", 1, PrepareResult.PREPARED)),
                    (Callable<Object>) () -> groupService.activate(gid,
                            new GroupActivateRequest("act-" + seq, "op", 1))));

            assertThat(results.get(0)).as("准备回执始终成功").isNotInstanceOf(Exception.class);
            Object activateResult = results.get(1);
            if (activateResult instanceof ApiException ae) {
                // 激活先到：未齐备 422，不占键；准备已就绪后同键重试成功
                assertThat(ae.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(ae.code()).isEqualTo("GROUP_NOT_READY");
                GroupReleaseView retried = groupService.activate(gid,
                        new GroupActivateRequest("act-" + seq, "op", 1));
                assertThat(retried.status()).isEqualTo("ACTIVATED");
            } else {
                assertThat(groupService.get(gid).status()).isEqualTo("ACTIVATED");
            }
            // 终态一致：全组同代次 v2，无部分切换
            assertThat(deviceService.get(a).currentVersion()).isEqualTo("v2");
            assertThat(deviceService.get(b).currentVersion()).isEqualTo("v2");
            assertThat(groupService.get(gid).activationGeneration()).isEqualTo(1);
        }
    }
}
