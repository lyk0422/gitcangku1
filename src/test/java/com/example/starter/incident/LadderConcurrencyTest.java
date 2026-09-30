package com.example.starter.incident;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.incident.dto.Requests.LadderConfigRequest;
import com.example.starter.incident.dto.Requests.LadderDemoteRequest;
import com.example.starter.incident.dto.Requests.LadderScoreBatchRequest;
import com.example.starter.incident.dto.Requests.LadderScoreItem;
import com.example.starter.incident.dto.Requests.ReportRequest;
import com.example.starter.incident.dto.Requests.StatusRequest;
import com.example.starter.incident.dto.Requests.TakeoverRequest;
import com.example.starter.incident.dto.Responses.IncidentView;
import com.example.starter.incident.dto.Responses.LadderConfigView;
import com.example.starter.incident.dto.Responses.LadderStateView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 升级阶梯并发测试：同一事件并发降级最多一次生效；并发配置创建由版本唯一约束串行化；
 * 降级与事件关闭按事务提交顺序裁决。使用可控 Clock，不依赖真实睡眠。
 */
@SpringBootTest
@Import(ControllableClock.Config.class)
class LadderConcurrencyTest {

    private static final Instant T0 = Instant.parse("2026-09-30T00:00:00Z");

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private LadderService ladderService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM command_keys");
        jdbc.update("DELETE FROM incident_ladder_history");
        jdbc.update("DELETE FROM incident_ladder_states");
        jdbc.update("DELETE FROM incident_ladder_configs");
        jdbc.update("DELETE FROM incident_escalations");
        jdbc.update("DELETE FROM incident_status_history");
        jdbc.update("DELETE FROM incident_transfers");
        jdbc.update("DELETE FROM incident_actions");
        jdbc.update("DELETE FROM incident_task_blockers");
        jdbc.update("DELETE FROM incident_tasks");
        jdbc.update("DELETE FROM incidents");
        ((ControllableClock) clock).setInstant(T0);
    }

    private static String key() {
        return "CMD-" + UUID.randomUUID();
    }

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

    /** 建立等级 3、冷却已过的事件，返回事件键。 */
    private String escalatedIncident(String incidentKey) {
        ladderService.createConfig("ops",
                new LadderConfigRequest(key(), List.of(10, 20, 40, 70, 100), 30));
        incidentService.report(new ReportRequest(incidentKey, "S2", "并发阶梯", "r"));
        incidentService.takeover(incidentKey, "alice", new TakeoverRequest(key()));
        ladderService.updateScores("alice", new LadderScoreBatchRequest(key(),
                List.of(new LadderScoreItem(incidentKey, 45))));
        ((ControllableClock) clock).setInstant(T0.plus(31, ChronoUnit.MINUTES));
        return incidentKey;
    }

    @Test
    void concurrentDemotes_sameIncident_atMostOneSucceeds() throws Exception {
        for (int round = 0; round < 3; round++) {
            String ik = escalatedIncident("INC-D-" + round);
            List<Object> results = runConcurrently(List.of(
                    () -> ladderService.demote(ik, "bob", new LadderDemoteRequest(key(), null)),
                    () -> ladderService.demote(ik, "carol", new LadderDemoteRequest(key(), null))));
            long successes = results.stream().filter(LadderStateView.class::isInstance).count();
            long conflicts = results.stream().filter(ApiException.class::isInstance)
                    .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
            // 并发降级最多一次生效：败者因冷却起点被重置而 409
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            // 最终等级恰好降一级，历史恰好一条 DEESCALATE
            assertThat(ladderService.getLadder(ik).currentLevel()).isEqualTo(2);
            var entries = ladderService.getHistory(ik).entries();
            assertThat(entries).hasSize(2);
            assertThat(entries.get(1).changeType()).isEqualTo("DEESCALATE");
            assertThat(entries.get(1).fromLevel()).isEqualTo(3);
            assertThat(entries.get(1).toLevel()).isEqualTo(2);
            ((ControllableClock) clock).setInstant(T0);
        }
    }

    @Test
    void concurrentConfigCreation_versionUniquenessHolds() throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> ladderService.createConfig("ops",
                    new LadderConfigRequest(key(), List.of(10, 20, 40, 70, 100), 30)));
        }
        List<Object> results = runConcurrently(tasks);
        long successes = results.stream().filter(LadderConfigView.class::isInstance).count();
        long conflicts = results.stream().filter(ApiException.class::isInstance)
                .map(ApiException.class::cast).filter(e -> e.status().value() == 409).count();
        // 版本号唯一约束兜底：并发同版本只有一个成功
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(2);
        assertThat(ladderService.currentConfig().configVersion()).isEqualTo(1);
        // 失败者更换 commandKey 重试后获得下一版本
        LadderConfigView retry = ladderService.createConfig("ops",
                new LadderConfigRequest(key(), List.of(10, 20, 40, 70, 100), 30));
        assertThat(retry.configVersion()).isEqualTo(2);
    }

    @Test
    void concurrentDemoteAndClose_commitOrderAdjudicates() throws Exception {
        for (int round = 0; round < 3; round++) {
            String ik = escalatedIncident("INC-DC-" + round);
            // 推进到 RESOLVED，使关闭与降级均可竞争
            incidentService.changeStatus(ik, "alice", new StatusRequest(key(), "CONTAINED"));
            incidentService.changeStatus(ik, "alice", new StatusRequest(key(), "RESOLVED"));
            List<Object> results = runConcurrently(List.of(
                    () -> ladderService.demote(ik, "bob", new LadderDemoteRequest(key(), null)),
                    () -> incidentService.changeStatus(ik, "alice",
                            new StatusRequest(key(), "CLOSED"))));
            Object demoteResult = results.get(0);
            Object closeResult = results.get(1);
            // 关闭始终可提交（RESOLVED → CLOSED 与阶梯无冲突）
            assertThat(closeResult).isInstanceOf(IncidentView.class);
            assertThat(((IncidentView) closeResult).status()).isEqualTo("CLOSED");
            if (demoteResult instanceof LadderStateView view) {
                // 降级先提交：等级 2，随后关闭成功
                assertThat(view.currentLevel()).isEqualTo(2);
                assertThat(ladderService.getLadder(ik).currentLevel()).isEqualTo(2);
            } else {
                // 关闭先提交：降级 422，等级保持 3，不留部分结果
                assertThat(demoteResult).isInstanceOf(ApiException.class);
                assertThat(((ApiException) demoteResult).status().value()).isEqualTo(422);
                assertThat(ladderService.getLadder(ik).currentLevel()).isEqualTo(3);
                assertThat(ladderService.getHistory(ik).entries()).hasSize(1);
            }
            ((ControllableClock) clock).setInstant(T0);
        }
    }
}
