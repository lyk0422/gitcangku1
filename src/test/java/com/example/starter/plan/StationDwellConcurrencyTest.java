package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ActiveStationDwellView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.StationConfigRequest;
import com.example.starter.plan.web.dto.StationDwellRequest;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
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
 * 站台容量与站台配置的并发裁决测试（真实 H2 内存库）：
 * 并发发布不得超卖容量；并发配置修订按提交顺序裁决、落后提交 409；
 * 并发取消与发布按提交顺序释放/占用容量。以 CountDownLatch 协调真实操作并设超时，
 * 成功标准为最终业务数据，而非打印或休眠。
 */
@SpringBootTest
class StationDwellConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishesNeverOversellPlatformCapacity() throws Exception {
        String station = key("ST");
        service.putStationConfig(station,
                new StationConfigRequest(key("REQ"), 0, 2, null, "alice"));
        int contenders = 5;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String sk = key("SCH");
            service.createDraft(new CreatePlanRequest(key("REQ"), sk, DAY,
                    List.of(occ(key("SEC"), 8, 9)),
                    List.of(dwell("G" + i, station, 8, 10, 8, 30)), null));
            scheduleKeys.add(sk);
        }

        List<Outcome> outcomes = runConcurrently(scheduleKeys.stream()
                .<Callable<Outcome>>map(sk -> () -> {
                    try {
                        service.publish(sk, key("REQ"), null);
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        assertThat(e.code()).isEqualTo("PLATFORM_CAPACITY_FULL");
                        return Outcome.REJECTED;
                    }
                }).toList());

        long published = outcomes.stream().filter(o -> o == Outcome.OK).count();
        long rejected = outcomes.stream().filter(o -> o == Outcome.REJECTED).count();
        assertThat(published).isEqualTo(2);
        assertThat(rejected).isEqualTo(contenders - 2);

        // 最终生效站停恰好等于容量，失败计划仍为草稿
        List<ActiveStationDwellView> active = service.getStationDiagnostics(station, DAY).activeDwells();
        assertThat(active).hasSize(2);
        long draftCount = scheduleKeys.stream()
                .filter(sk -> service.getPlan(sk).status().equals("DRAFT")).count();
        assertThat(draftCount).isEqualTo(contenders - 2);

        // 落库的发布快照也恰好 2 份
        Integer snapshotRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_snapshot_station_dwell WHERE station_id = ?",
                Integer.class, station);
        assertThat(snapshotRows).isEqualTo(2);
    }

    @Test
    void concurrentStationConfigRevisionsApplyInCommitOrder() throws Exception {
        String station = key("ST");
        service.putStationConfig(station,
                new StationConfigRequest(key("REQ"), 0, 1, null, "alice"));

        int contenders = 4;
        List<Outcome> outcomes = runConcurrently(java.util.stream.IntStream.range(0, contenders)
                .<Callable<Outcome>>mapToObj(i -> () -> {
                    try {
                        // 全部基于版本 1 并发修订，仅第一个提交者成功，其余因版本落后 409
                        service.putStationConfig(station, new StationConfigRequest(
                                key("REQ"), i + 1, i + 1, 1, "op-" + i));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.REJECTED;
                    }
                }).toList());

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.REJECTED).count())
                .isEqualTo(contenders - 1);
        assertThat(service.getStationConfig(station).version()).isEqualTo(2);

        // 表内无半成品：仅一行当前配置
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_station_config WHERE station_id = ?",
                Integer.class, station);
        assertThat(rows).isEqualTo(1);

        // 获胜者可以再以版本 2 继续修订，证明按提交顺序裁决后状态一致
        service.putStationConfig(station, new StationConfigRequest(
                key("REQ"), 9, 9, 2, "final"));
        assertThat(service.getStationConfig(station).version()).isEqualTo(3);
    }

    @Test
    void concurrentCancelAndPublishHandoverCapacityExactlyOnce() throws Exception {
        String station = key("ST");
        service.putStationConfig(station,
                new StationConfigRequest(key("REQ"), 0, 1, null, "alice"));
        String holder = key("SCH");
        service.createDraft(new CreatePlanRequest(key("REQ"), holder, DAY,
                List.of(occ(key("SEC"), 8, 9)),
                List.of(dwell("GH", station, 8, 10, 8, 30)), null));
        service.publish(holder, key("REQ"), null);

        String contender = key("SCH");
        service.createDraft(new CreatePlanRequest(key("REQ"), contender, DAY,
                List.of(occ(key("SEC"), 9, 10)),
                List.of(dwell("GN", station, 8, 10, 8, 30)), null));

        // 取消占用者与发布竞争者同时起跑：容量为 1，最终恰有一方持有该时段容量
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    service.cancel(holder, key("REQ"), null);
                    return Outcome.OK;
                },
                () -> {
                    try {
                        service.publish(contender, key("REQ"), null);
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                        return Outcome.REJECTED;
                    }
                }));

        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        List<ActiveStationDwellView> active = service.getStationDiagnostics(station, DAY).activeDwells();
        if (outcomes.get(1) == Outcome.OK) {
            // 取消先提交：旧释放、新占用，生效的是竞争者
            assertThat(active).hasSize(1);
            assertThat(active.get(0).scheduleKey()).isEqualTo(contender);
            assertThat(service.getPlan(holder).status()).isEqualTo("CANCELLED");
            assertThat(service.getPlan(contender).status()).isEqualTo("PUBLISHED");
        } else {
            // 发布先提交：竞争者 429 保持草稿，取消随后生效，站台清空
            assertThat(active).isEmpty();
            assertThat(service.getPlan(holder).status()).isEqualTo("CANCELLED");
            assertThat(service.getPlan(contender).status()).isEqualTo("DRAFT");
            // 失败不占键且未留半成品：竞争者无快照
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rail_publish_snapshot s"
                            + " JOIN rail_day_plan p ON p.id = s.plan_id"
                            + " WHERE p.schedule_key = ?",
                    Integer.class, contender)).isZero();
        }
    }

    @Test
    void replaceDwellsAndPublishDoNotOversellAcrossVersions() throws Exception {
        String station = key("ST");
        service.putStationConfig(station,
                new StationConfigRequest(key("REQ"), 0, 1, null, "alice"));
        // 已发布计划持有容量
        String first = key("SCH");
        service.createDraft(new CreatePlanRequest(key("REQ"), first, DAY,
                List.of(occ(key("SEC"), 8, 9)),
                List.of(dwell("G1", station, 8, 10, 8, 40)), null));
        service.publish(first, key("REQ"), null);

        String second = key("SCH");
        service.createDraft(new CreatePlanRequest(key("REQ"), second, DAY,
                List.of(occ(key("SEC"), 9, 10)),
                List.of(dwell("G2", station, 8, 10, 8, 40)), null));

        // 并发：对 second 替换为不冲突站停（09:00 之后），同时尝试按旧登记发布
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.replaceOccupancies(second, new UpdateOccupanciesRequest(
                                key("REQ"), 1, List.of(occ(key("SEC"), 9, 10)),
                                List.of(dwell("G2", station, 9, 10, 9, 40)), null));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.REJECTED;
                    }
                },
                () -> {
                    try {
                        service.publish(second, key("REQ"), null);
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 更新先提交：版本不匹配导致发布侧无影响；按旧登记发布超容量 429
                        assertThat(e.status()).isIn(HttpStatus.TOO_MANY_REQUESTS,
                                HttpStatus.CONFLICT);
                        return Outcome.REJECTED;
                    }
                }));

        // 最终容量不超卖：生效站停区间两两不得重叠（左闭右开），峰值并发不超过容量 1
        List<ActiveStationDwellView> active = service.getStationDiagnostics(station, DAY).activeDwells();
        for (int i = 0; i < active.size(); i++) {
            for (int j = i + 1; j < active.size(); j++) {
                boolean overlap = active.get(i).arrivalUtc().isBefore(active.get(j).departureUtc())
                        && active.get(j).arrivalUtc().isBefore(active.get(i).departureUtc());
                assertThat(overlap)
                        .as("生效站停 %s 与 %s 重叠，站台容量被超卖",
                                active.get(i).scheduleKey(), active.get(j).scheduleKey())
                        .isFalse();
            }
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        REJECTED
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
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        return outcomes;
    }

    private static OccupancyRequest occ(String section, int startHour, int endHour) {
        return new OccupancyRequest("G-" + UUID.randomUUID(), section,
                at(startHour, 0), at(endHour, 0));
    }

    private static StationDwellRequest dwell(String train, String station,
                                             int arrivalHour, int arrivalMinute,
                                             int departureHour, int departureMinute) {
        return new StationDwellRequest(train, station,
                at(arrivalHour, arrivalMinute), at(departureHour, departureMinute));
    }

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
