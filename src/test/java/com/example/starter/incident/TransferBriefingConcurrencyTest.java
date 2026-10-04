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

import com.example.starter.incident.dto.Requests.BriefingRefreshRequest;
import com.example.starter.incident.dto.Requests.ReadbackRequest;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Requests.TransferAcceptRequest;
import com.example.starter.incident.dto.Requests.TransferRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 交接简报并发测试：刷新与接收并发只能接受一份当前简报；
 * 并发接收同一简报只生效一次，简报与回读证据随交接原子冻结。
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
        jdbc.update("DELETE FROM transfer_readbacks");
        jdbc.update("DELETE FROM transfer_briefings");
        jdbc.update("DELETE FROM incident_escalations");
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

    private void commanding(String incidentKey, String commander) {
        service.report(new ReportRequest(incidentKey, "S1", "并发交接", "reporter-1"));
        service.takeover(incidentKey, commander, new TakeoverRequest(key()));
    }

    /**
     * 并发提交一批任务并收集结果（成功值或异常），全部线程就绪后同时放行。
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
        String ik = "INC-BC-001";
        commanding(ik, "alice");
        service.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob"));
        var v1 = service.currentBriefing(ik, "bob");
        service.readbackBriefing(ik, "bob", new ReadbackRequest(key(), v1.id(), v1.digest()));

        List<Object> results = runConcurrently(List.of(
                () -> service.refreshBriefing(ik, "alice",
                        new BriefingRefreshRequest(key(), "并发刷新")),
                () -> service.acceptTransfer(ik, "bob",
                        new TransferAcceptRequest(key(), v1.id()))));

        long successes = results.stream().filter(r -> !(r instanceof Exception)).count();
        long failures = results.stream().filter(ApiException.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(failures).isEqualTo(1);

        IncidentView end = service.get(ik);
        if ("bob".equals(end.commander())) {
            // 接受先提交：v1 随交接冻结，刷新因无待交接记录失败
            assertThat(service.getBriefing(ik, v1.id(), "bob").status()).isEqualTo("FROZEN");
            assertThat(service.getBriefing(ik, v1.id(), "bob").readbackBy()).isEqualTo("bob");
        } else {
            // 刷新先提交：v1 过期接收失败，指挥权未切换，v2 成为当前简报
            assertThat(end.commander()).isEqualTo("alice");
            assertThat(service.currentBriefing(ik, "bob").version()).isEqualTo(2);
        }
    }

    @Test
    void concurrentAccept_sameBriefing_exactlyOneWins() throws Exception {
        String ik = "INC-BC-002";
        commanding(ik, "alice");
        service.initiateTransfer(ik, "alice", new TransferRequest(key(), "bob"));
        var v1 = service.currentBriefing(ik, "bob");
        service.readbackBriefing(ik, "bob", new ReadbackRequest(key(), v1.id(), v1.digest()));

        List<Callable<IncidentView>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> service.acceptTransfer(ik, "bob",
                    new TransferAcceptRequest(key(), v1.id())));
        }
        List<Object> results = runConcurrently(tasks);

        long successes = results.stream().filter(IncidentView.class::isInstance).count();
        long failures = results.stream().filter(ApiException.class::isInstance).count();
        assertThat(successes).isEqualTo(1);
        assertThat(failures).isEqualTo(2);
        assertThat(service.get(ik).commander()).isEqualTo("bob");
        // 简报只冻结一次，回读确认只有一条
        Integer frozen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_briefings WHERE status = 'FROZEN'", Integer.class);
        assertThat(frozen).isEqualTo(1);
        Integer readbacks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfer_readbacks WHERE briefing_id = ?",
                Integer.class, v1.id());
        assertThat(readbacks).isEqualTo(1);
    }
}