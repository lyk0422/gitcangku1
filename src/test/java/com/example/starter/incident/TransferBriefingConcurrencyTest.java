package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TaskCreateRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferReadbackRequest;
import com.example.starter.incident.dto.Requests.TransferRefreshRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.BriefingView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 交接简报并发测试：验证事件行锁串行化下，刷新与接收并发只接受一份当前简报，
 * 并发回读只确认一次，并发同键接收只生效一次。
 */
@SpringBootTest
class TransferBriefingConcurrencyTest {

    @Autowired
    private IncidentService service;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM transfer_briefings");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

    private void setup(String ik, String commander) {
        service.report(new ReportRequest(ik, "S2", "缓存穿透", "reporter-1"));
        service.takeover(ik, commander, new TakeoverRequest(key()));
    }

    private BriefingView initiateAndReadback(String ik, String to) {
        service.initiateTransfer(ik, "alice", new TransferRequest(key(), to, "交班"));
        BriefingView b = service.currentBriefing(ik, to);
        service.readbackBriefing(ik, to, new TransferReadbackRequest(key(), b.id(), b.digest()));
        return b;
    }

    /**
     * 并发提交一批任务并收集结果（成功值或异常），全部在统一闸门后出发，带超时。
     */
    private static <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
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
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRefreshAndAccept_onlyOneCurrentBriefingAccepted() throws Exception {
        String ik = "INC-CB-01";
        setup(ik, "alice");
        service.createTask(ik, "alice", new TaskCreateRequest(key(), "T1", "G", "t", List.of()));
        BriefingView v1 = initiateAndReadback(ik, "bob");

        List<Object> results = runConcurrently(List.of(
                () -> service.refreshBriefing(ik, "alice", new TransferRefreshRequest(key(), 1L, null)),
                () -> service.acceptTransfer(ik, "bob", new TransferAcceptRequest(key(), v1.id()))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);

        IncidentView end = service.get(ik);
        if ("bob".equals(end.commander())) {
            // 接收先提交：简报 v1 已冻结，刷新失败，指挥权只切换一次
            BriefingView frozen = service.getBriefing(ik, v1.id(), "bob");
            assertThat(frozen.status()).isEqualTo("FROZEN");
            assertThat(frozen.readbackBy()).isEqualTo("bob");
            Integer current = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM transfer_briefings WHERE status = 'CURRENT'",
                    Integer.class);
            assertThat(current).isZero();
        } else {
            // 刷新先提交：v1 被取代，接收引用旧简报失败，v2 为唯一当前简报
            assertThat(end.commander()).isEqualTo("alice");
            BriefingView v2 = service.currentBriefing(ik, "alice");
            assertThat(v2.version()).isEqualTo(2);
            assertThat(service.getBriefing(ik, v1.id(), "alice").status()).isEqualTo("SUPERSEDED");
        }
    }

    @Test
    void concurrentReadbacks_distinctKeys_singleConfirmation() throws Exception {
        String ik = "INC-CB-02";
        setup(ik, "alice");
        service.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob"));
        BriefingView b = service.currentBriefing(ik, "bob");

        List<Callable<BriefingView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.readbackBriefing(ik, "bob",
                    new TransferReadbackRequest(key(), b.id(), b.digest())));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(BriefingView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(2);
        // 只确认一次
        BriefingView after = service.currentBriefing(ik, "bob");
        assertThat(after.readbackBy()).isEqualTo("bob");
        Integer confirmed = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_briefings WHERE readback_by IS NOT NULL",
                Integer.class);
        assertThat(confirmed).isEqualTo(1);
    }

    @Test
    void concurrentAccepts_sameCommandKey_singleEffect() throws Exception {
        String ik = "INC-CB-03";
        setup(ik, "alice");
        BriefingView b = initiateAndReadback(ik, "bob");
        String commandKey = key();

        List<Callable<IncidentView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.acceptTransfer(ik, "bob",
                    new TransferAcceptRequest(commandKey, b.id())));
        }
        List<Object> results = runConcurrently(tasks);

        List<IncidentView> successes = results.stream().filter(IncidentView.class::isInstance)
                .map(IncidentView.class::cast).toList();
        assertThat(successes).isNotEmpty();
        // 所有成功响应一致，指挥权只切换一次
        assertThat(successes).allSatisfy(v -> assertThat(v).isEqualTo(successes.get(0)));
        assertThat(service.get(ik).commander()).isEqualTo("bob");
        var transfers = service.history(ik).transfers();
        assertThat(transfers).filteredOn(t -> "ACCEPTED".equals(t.status())).hasSize(1);
        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM command_keys WHERE command_key = ?", Integer.class, commandKey);
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    void concurrentRefreshes_distinctKeys_singleNewVersion() throws Exception {
        String ik = "INC-CB-04";
        setup(ik, "alice");
        service.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob"));

        List<Callable<BriefingView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.refreshBriefing(ik, "alice",
                    new TransferRefreshRequest(key(), 1L, null)));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(BriefingView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .filter(e -> e.status().value() == 409).count();
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(2);
        // 只产生一个 v2，当前简报唯一
        BriefingView current = service.currentBriefing(ik, "alice");
        assertThat(current.version()).isEqualTo(2);
        Integer currentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_briefings WHERE status = 'CURRENT'", Integer.class);
        assertThat(currentCount).isEqualTo(1);
    }
}