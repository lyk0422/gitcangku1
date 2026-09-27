package com.example.starter.station;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.StationDiagnosticsResponse;
import com.example.starter.plan.web.dto.StationStopRequest;
import com.example.starter.station.service.StationService;
import com.example.starter.station.web.dto.StationConfigRequest;
import com.example.starter.station.web.dto.StationHistoryResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 站停能力并发裁决测试（真实 H2 数据库与真实线程）：
 * 并发发布同站台不得超卖容量；并发站台配置修订按提交顺序累加版本且历史完整；
 * 配置修订与发布并发时，发布快照与所适用的配置版本始终一致。
 */
@SpringBootTest
class StationDwellConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 27);

    @Autowired
    private PlanService planService;

    @Autowired
    private StationService stationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishSameStationNeverOversellsCapacity() throws Exception {
        String station = key("ST");
        stationService.upsertConfig(station,
                new StationConfigRequest(key("REQ"), 0, 2, null));

        int contenders = 5;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String scheduleKey = key("SCH");
            createDraftWithStop(scheduleKey, key("SEC"), "G" + i, station);
            scheduleKeys.add(scheduleKey);
        }

        List<Outcome> outcomes = runConcurrently(scheduleKeys.stream()
                .<Callable<Outcome>>map(sk -> () -> {
                    try {
                        planService.publish(sk, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        assertThat(e.code()).isEqualTo("PLATFORM_CAPACITY_EXCEEDED");
                        return Outcome.REJECTED;
                    }
                }).toList());

        long published = outcomes.stream().filter(o -> o == Outcome.OK).count();
        long rejected = outcomes.stream().filter(o -> o == Outcome.REJECTED).count();
        assertThat(published).isEqualTo(2);
        assertThat(rejected).isEqualTo(contenders - 2);

        // 最终站台实际峰值并行停靠数恰好等于容量，不存在超卖与半成品快照
        StationDiagnosticsResponse diag = planService.getStationDiagnostics(DAY, station);
        assertThat(diag.peakConcurrent()).isEqualTo(2);
        assertThat(diag.remaining()).isZero();
        Integer snapshotRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_published_stop s JOIN rail_day_plan p ON p.id = s.plan_id"
                        + " WHERE s.station_id = ? AND p.status = 'PUBLISHED'",
                Integer.class, station);
        assertThat(snapshotRows).isEqualTo(2);
        // 被拒计划全部保持草稿
        for (String sk : scheduleKeys) {
            String status = planService.getPlan(sk).status();
            assertThat(status).isIn("PUBLISHED", "DRAFT");
        }
    }

    @Test
    void concurrentStationRevisionsSerialiseAndAppendEveryVersion() throws Exception {
        String station = key("ST");
        stationService.upsertConfig(station,
                new StationConfigRequest(key("REQ"), 1, 1, "init"));

        int revisions = 4;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < revisions; i++) {
            final int dwell = 2 + i;
            tasks.add(() -> {
                stationService.upsertConfig(station,
                        new StationConfigRequest(key("REQ"), dwell, 1, "op" + dwell));
                return Outcome.OK;
            });
        }
        runConcurrently(tasks);

        // 版本严格连续到 1+revisions，不可变历史无缺失
        StationHistoryResponse history = stationService.getHistory(station);
        assertThat(history.versions()).hasSize(revisions + 1);
        for (int v = 1; v <= revisions + 1; v++) {
            assertThat(history.versions().get(v - 1).version()).isEqualTo(v);
        }
        assertThat(stationService.getConfig(station).version()).isEqualTo(revisions + 1);
    }

    @Test
    void concurrentRevisionWithSameRequestKeyReturnsFirstResult() throws Exception {
        String station = key("ST");
        stationService.upsertConfig(station,
                new StationConfigRequest(key("REQ"), 1, 1, null));

        String requestKey = key("REQ");
        int threads = 3;
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> stationService.upsertConfig(station,
                    new StationConfigRequest(requestKey, 7, 2, null)).version());
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<Integer> versions = new ArrayList<>();
        for (Future<Integer> f : futures) {
            versions.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次提交产生的同一版本，且只追加一个版本
        assertThat(versions).containsOnly(versions.get(0));
        assertThat(stationService.getHistory(station).versions()).hasSize(2);
    }

    @Test
    void concurrentPublishAndRevisionProduceInternallyConsistentSnapshot() throws Exception {
        String station = key("ST");
        stationService.upsertConfig(station,
                new StationConfigRequest(key("REQ"), 0, 5, "v1"));

        String scheduleKey = key("SCH");
        createDraftWithStop(scheduleKey, key("SEC"), "G1", station);

        // 发布（下限 0 可过）与收紧到下限 60 的修订并发：无论谁先提交，快照必须与所适用版本一致
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    planService.publish(scheduleKey, key("REQ"));
                    return Outcome.OK;
                },
                () -> {
                    stationService.upsertConfig(station,
                            new StationConfigRequest(key("REQ"), 60, 5, "v2"));
                    return Outcome.OK;
                }));
        assertThat(outcomes).containsOnly(Outcome.OK);

        // 发布必然成功（其读到的规则版本允许该站停）；快照固化的 (version, minDwell)
        // 必须与 rail_station_version 中该版本行严格一致，规则修订不改写它
        assertThat(planService.getPlan(scheduleKey).status()).isEqualTo("PUBLISHED");
        Integer frozenVersion = jdbc.queryForObject(
                "SELECT station_version FROM rail_published_stop s JOIN rail_day_plan p"
                        + " ON p.id = s.plan_id WHERE p.schedule_key = ?",
                Integer.class, scheduleKey);
        Integer frozenDwell = jdbc.queryForObject(
                "SELECT min_dwell_minutes FROM rail_published_stop s JOIN rail_day_plan p"
                        + " ON p.id = s.plan_id WHERE p.schedule_key = ?",
                Integer.class, scheduleKey);
        Integer versionDwell = jdbc.queryForObject(
                "SELECT min_dwell_minutes FROM rail_station_version"
                        + " WHERE station_id = ? AND version = ?",
                Integer.class, station, frozenVersion);
        assertThat(frozenDwell).isEqualTo(versionDwell);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        REJECTED
    }

    private void createDraftWithStop(String scheduleKey, String section, String trainNo,
                                     String station) {
        List<OccupancyRequest> occupancies = List.of(new OccupancyRequest(
                trainNo, section, at(8), at(12)));
        List<StationStopRequest> stops = List.of(new StationStopRequest(
                trainNo, station, at(9), at(10)));
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                occupancies, stops, null));
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
