package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.StationService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.StationConfigRequest;
import com.example.starter.plan.web.dto.StationStopRequest;
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

/**
 * 站停容量与站点配置的并发裁决测试（真实 H2 内存库）：
 * 并发到发不得超卖站台容量；站点配置修订按提交顺序裁决，失败不留半成品。
 * 所有并发用 CountDownLatch 协调真实提交，断言最终业务数据并设置超时。
 */
@SpringBootTest
class StationDwellConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);

    @Autowired
    private PlanService planService;

    @Autowired
    private StationService stationService;

    @Test
    void concurrentPublishesToSamePlatformNeverOversell() throws Exception {
        String station = key("ST");
        stationService.saveConfig(station, new StationConfigRequest(
                key("REQ"), "dispatcher", 0, 1, null));

        int contenders = 4;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String scheduleKey = key("SCH");
            createDraftWithStop(scheduleKey, "G" + i, station, at(10, 0), at(11, 0));
            scheduleKeys.add(scheduleKey);
        }

        List<Outcome> outcomes = runConcurrently(scheduleKeys.stream()
                .<Callable<Outcome>>map(sk -> () -> {
                    try {
                        planService.publish(sk, key("REQ"), "dispatcher");
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        assertThat(e.code()).isEqualTo("PLATFORM_CAPACITY_EXCEEDED");
                        return Outcome.REJECTED;
                    }
                }).toList());

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.REJECTED).count())
                .isEqualTo(contenders - 1);

        // 最终站台恰好一张生效快照，其余计划保持草稿
        long published = scheduleKeys.stream()
                .filter(sk -> planService.getPlan(sk).status().equals("PUBLISHED"))
                .count();
        long drafts = scheduleKeys.stream()
                .filter(sk -> planService.getPlan(sk).status().equals("DRAFT"))
                .count();
        assertThat(published).isEqualTo(1);
        assertThat(drafts).isEqualTo(contenders - 1);
        var diagnostics = stationService.getDiagnostics(station, DAY);
        assertThat(diagnostics.publishedCount()).isEqualTo(1);
        assertThat(diagnostics.peakConcurrent()).isEqualTo(1);
    }

    @Test
    void concurrentStationRevisionsApplyInCommitOrder() throws Exception {
        String station = key("ST");
        stationService.saveConfig(station, new StationConfigRequest(
                key("REQ"), "dispatcher", 5, 2, null));

        int contenders = 3;
        List<Outcome> outcomes = runConcurrently(java.util.stream.IntStream.range(0, contenders)
                .<Callable<Outcome>>mapToObj(i -> () -> {
                    try {
                        stationService.saveConfig(station, new StationConfigRequest(
                                key("REQ"), "dispatcher", 6 + i, 2, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.REJECTED;
                    }
                }).toList());

        // 恰好一次修订成功，版本到 2；其余因 expectedVersion=1 失配 409
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.REJECTED).count())
                .isEqualTo(contenders - 1);
        var config = stationService.getConfig(station);
        assertThat(config.version()).isEqualTo(2);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        REJECTED
    }

    private void createDraftWithStop(String scheduleKey, String trainNo, String station,
                                     Instant arrival, Instant departure) {
        OccupancyRequest occupancy = new OccupancyRequest(trainNo, key("SEC"), at(8), at(12));
        StationStopRequest stop = new StationStopRequest(trainNo, station, arrival, departure);
        planService.createDraft(new CreatePlanRequest(key("REQ"), "dispatcher", scheduleKey, DAY,
                List.of(occupancy), List.of(stop)));
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
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
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

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
