package com.example.starter.firmware;

import com.example.starter.firmware.api.ActivateGroupRequest;
import com.example.starter.firmware.api.CancelGroupRequest;
import com.example.starter.firmware.api.CreateGroupReleaseRequest;
import com.example.starter.firmware.api.GroupReleaseView;
import com.example.starter.firmware.api.PrepareReceiptRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.GroupReleaseStatus;
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
 * 组预备屏障并发测试：准备、取消与激活竞争只产生一个完整终态，真实打到 H2 事务与唯一约束上。
 */
@SpringBootTest
class GroupReleaseConcurrencyTest {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private GroupReleaseService groupReleaseService;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device_group_member");
        jdbc.update("DELETE FROM device_group_release");
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
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
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

    private long newGroup(String suffix, String... deviceIds) {
        for (String deviceId : deviceIds) {
            deviceService.register(new RegisterDeviceRequest(
                    "req-d-" + deviceId + "-" + suffix, deviceId, "m1", "v1", 1));
        }
        return groupReleaseService.create(new CreateGroupReleaseRequest(
                "req-g-" + suffix, "op", "m1", "v2", List.of(deviceIds))).groupId();
    }

    private void prepared(long groupId, String deviceId, String requestId) {
        groupReleaseService.prepareReceipt(groupId, deviceId,
                new PrepareReceiptRequest(requestId, "op", 1, PrepareResult.PREPARED));
    }

    @Test
    void 并发激活与取消_只产生一个完整终态() throws Exception {
        int activated = 0;
        int cancelled = 0;
        for (int round = 0; round < 10; round++) {
            String d1 = "a" + round;
            String d2 = "b" + round;
            long groupId = newGroup("r" + round, d1, d2);
            prepared(groupId, d1, "req-p1-" + round);
            prepared(groupId, d2, "req-p2-" + round);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    () -> groupReleaseService.activate(groupId,
                            new ActivateGroupRequest("req-act-" + seq, "op", 1)),
                    () -> groupReleaseService.cancel(groupId,
                            new CancelGroupRequest("req-can-" + seq, "op", 1))));

            GroupReleaseStatus finalStatus = groupReleaseService.findGroup(groupId).status();
            String v1 = deviceService.get(d1).currentVersion();
            String v2 = deviceService.get(d2).currentVersion();
            if (finalStatus == GroupReleaseStatus.ACTIVATED) {
                // 激活先提交：全组同代次 v2，取消 409
                activated++;
                assertThat(results.get(0)).isNotInstanceOf(Exception.class);
                assertThat(results.get(1)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.code()).isEqualTo("GROUP_ALREADY_ACTIVATED"));
                assertThat(v1).isEqualTo("v2");
                assertThat(v2).isEqualTo("v2");
            } else {
                // 取消先提交：整组 CANCELLED，设备仍 v1，激活 409
                cancelled++;
                assertThat(finalStatus).isEqualTo(GroupReleaseStatus.CANCELLED);
                assertThat(results.get(0)).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.code()).isEqualTo("GROUP_CANCELLED"));
                assertThat(results.get(1)).isNotInstanceOf(Exception.class);
                assertThat(v1).isEqualTo("v1");
                assertThat(v2).isEqualTo("v1");
            }
            // 不出现部分设备切版本的中间态
            assertThat(v1).isEqualTo(v2);
        }
        assertThat(activated + cancelled).isEqualTo(10);
    }

    @Test
    void 并发同requestId激活_重放一致且只激活一次() throws Exception {
        long groupId = newGroup("same", "d1", "d2");
        prepared(groupId, "d1", "req-p1");
        prepared(groupId, "d2", "req-p2");

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> groupReleaseService.activate(groupId,
                    new ActivateGroupRequest("req-act-same", "op", 1)));
        }
        List<Object> results = runConcurrently(tasks);
        assertThat(results).noneMatch(r -> r instanceof Exception);

        GroupReleaseView view = groupReleaseService.get(groupId);
        assertThat(view.status()).isEqualTo("ACTIVATED");
        assertThat(view.version()).isEqualTo(2);
        assertThat(view.members()).allMatch(m -> m.activationGeneration() != null
                && m.activationGeneration().equals(view.activationGeneration()));
        assertThat(deviceService.get("d1").currentVersion()).isEqualTo("v2");
        assertThat(deviceService.get("d2").currentVersion()).isEqualTo("v2");
    }

    @Test
    void 并发准备回执与失败回执_整组ABORTED且已暂存不切版本() throws Exception {
        for (int round = 0; round < 10; round++) {
            String d1 = "p" + round;
            String d2 = "q" + round;
            long groupId = newGroup("r" + round, d1, d2);
            final int seq = round;

            List<Object> results = runConcurrently(List.of(
                    () -> groupReleaseService.prepareReceipt(groupId, d1,
                            new PrepareReceiptRequest("req-ok-" + seq, "op", 1, PrepareResult.PREPARED)),
                    () -> groupReleaseService.prepareReceipt(groupId, d2,
                            new PrepareReceiptRequest("req-bad-" + seq, "op", 1, PrepareResult.FAILED))));

            // FAILED 回执必然成功；并发的 PREPARED 回执若落在 ABORTED 之后则被拒（组行锁串行，先到先赢）
            assertThat(results.get(1)).isNotInstanceOf(Exception.class);
            if (results.get(0) instanceof Exception e) {
                assertThat(e).isInstanceOfSatisfying(ApiException.class,
                        ae -> assertThat(ae.code()).isEqualTo("GROUP_NOT_PREPARING"));
            }
            GroupReleaseView view = groupReleaseService.get(groupId);
            assertThat(view.status()).isEqualTo("ABORTED");
            assertThat(view.failedDeviceId()).isEqualTo(d2);
            // 已暂存成员也不切版本
            assertThat(deviceService.get(d1).currentVersion()).isEqualTo("v1");
            assertThat(deviceService.get(d2).currentVersion()).isEqualTo("v1");
            // 终态后激活 409
            try {
                groupReleaseService.activate(groupId, new ActivateGroupRequest("req-late-" + seq, "op", 1));
                throw new AssertionError("ABORTED 后激活应失败");
            } catch (ApiException ae) {
                assertThat(ae.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ae.code()).isEqualTo("GROUP_ABORTED");
            }
        }
    }

    @Test
    void 并发建组_同设备至多进入一个活动组() throws Exception {
        deviceService.register(new RegisterDeviceRequest("req-d1", "d1", "m1", "v1", 1));
        deviceService.register(new RegisterDeviceRequest("req-d2", "d2", "m1", "v1", 1));
        deviceService.register(new RegisterDeviceRequest("req-d3", "d3", "m1", "v1", 1));

        List<Object> results = runConcurrently(List.of(
                () -> groupReleaseService.create(new CreateGroupReleaseRequest(
                        "req-g1", "op", "m1", "v2", List.of("d1", "d2"))),
                () -> groupReleaseService.create(new CreateGroupReleaseRequest(
                        "req-g2", "op", "m1", "v2", List.of("d2", "d3")))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream()
                .filter(r -> r instanceof ApiException ae && "DEVICE_IN_ACTIVE_GROUP".equals(ae.code()))
                .count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        Long active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_group_member WHERE device_id = 'd2'"
                        + " AND active_device IS NOT NULL", Long.class);
        assertThat(active).isEqualTo(1);
    }
}
