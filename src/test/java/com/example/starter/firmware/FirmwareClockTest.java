package com.example.starter.firmware;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.ShardInput;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.DigestRules;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.ShardReceiptRequest;
import com.example.starter.firmware.service.DeviceService;
import com.example.starter.firmware.service.ReleaseService;
import com.example.starter.firmware.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 可控时钟测试：分片接收时刻、完整性判定时刻、暂停/恢复时刻统一为 UTC（ISO-8601，结尾Z），
 * 左闭右开的时间语义不依赖系统时区；时钟推进后新事件得到新时刻，旧证据时刻不被改写。
 */
@SpringBootTest
class FirmwareClockTest {

    @Autowired
    private DeviceService deviceService;
    @Autowired
    private ReleaseService releaseService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private Clock clock;

    private static final Instant T1 = Instant.parse("2026-09-28T01:02:03.123456Z");
    private static final Instant T2 = Instant.parse("2026-09-28T04:05:06.654321Z");
    private static final Instant T3 = Instant.parse("2026-09-28T07:08:09Z");

    private static String d(String content) {
        return DigestRules.sha256Hex(content);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM task_shard_receipt");
        jdbc.update("DELETE FROM task_attempt");
        jdbc.update("DELETE FROM rollout_task");
        jdbc.update("DELETE FROM release_shard");
        jdbc.update("DELETE FROM release_pause_record");
        jdbc.update("DELETE FROM release_resume_record");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM device");
        jdbc.update("DELETE FROM idempotency_record");
    }

    @Test
    void 分片接收与判定时刻_UTC且时钟推进不改旧证据() {
        when(clock.instant()).thenReturn(T1, T2, T3);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        deviceService.register(new RegisterDeviceRequest("rd", "d1", "m1", "1.0.0", 1));
        var shards = List.of(new ShardInput(0, d("s0")), new ShardInput(1, d("s1")));
        long releaseId = releaseService.create(new CreateReleaseRequest("rr", "m1", "1.0.0",
                "2.0.0", 100, 2, 50, DigestRules.aggregateOfOrderedDigests(List.of(d("s0"), d("s1"))),
                shards)).releaseId();
        long taskId = taskService.pull("d1", "rp").task().taskId();

        // T1：锁定时刻
        String lockedAt = jdbc.queryForObject(
                "SELECT manifest_locked_at_utc FROM release_order WHERE id = ?", String.class, releaseId);
        assertThat(lockedAt).isEqualTo(T1.toString());

        // T2：第一片接收
        TaskView afterFirst = taskService.receiveShard(taskId,
                new ShardReceiptRequest("rs0", 0, d("s0")));
        assertThat(afterFirst.status()).isEqualTo("PENDING");
        String receivedAt = jdbc.queryForObject(
                "SELECT received_at_utc FROM task_shard_receipt WHERE task_id = ? AND shard_index = 0",
                String.class, taskId);
        assertThat(receivedAt).isEqualTo(T2.toString());

        // T3：最后一片，判定可安装
        TaskView installable = taskService.receiveShard(taskId,
                new ShardReceiptRequest("rs1", 1, d("s1")));
        assertThat(installable.attemptFinalizedAtUtc()).isEqualTo(T3.toString());
        assertThat(installable.attemptFinalizedAtUtc()).endsWith("Z");

        // 旧证据时刻不被后续操作改写
        assertThat(jdbc.queryForObject(
                "SELECT received_at_utc FROM task_shard_receipt WHERE task_id = ? AND shard_index = 0",
                String.class, taskId)).isEqualTo(T2.toString());
        assertThat(jdbc.queryForObject(
                "SELECT finalized_at_utc FROM task_attempt WHERE task_id = ? AND attempt_no = 1",
                String.class, taskId)).isEqualTo(T3.toString());
    }

    @Test
    void 暂停时刻_UTC来自可控时钟() {
        when(clock.instant()).thenReturn(T1, T1, T2);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);

        deviceService.register(new RegisterDeviceRequest("rd1", "d1", "m1", "1.0.0", 1));
        deviceService.register(new RegisterDeviceRequest("rd2", "d2", "m1", "1.0.0", 2));
        long releaseId = releaseService.create(new CreateReleaseRequest("rr", "m1", "1.0.0",
                "2.0.0", 100, 2, 50)).releaseId();
        long t1 = taskService.pull("d1", "rp1").task().taskId();
        long t2 = taskService.pull("d2", "rp2").task().taskId();

        taskService.receipt(t1, new ReceiptRequest("rf1", ReceiptResult.FAILED));
        taskService.receipt(t2, new ReceiptRequest("rf2", ReceiptResult.FAILED));

        String pausedAt = jdbc.queryForObject(
                "SELECT paused_at_utc FROM release_pause_record WHERE release_id = ?",
                String.class, releaseId);
        assertThat(pausedAt).isEqualTo(T2.toString());
        assertThat(releaseService.findOrder(releaseId).status().name()).isEqualTo("PAUSED");
    }
}
