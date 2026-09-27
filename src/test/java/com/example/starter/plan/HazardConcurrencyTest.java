package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.HazardConfigService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchRescheduleItemRequest;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.HazardCarRequest;
import com.example.starter.plan.web.dto.HazardConfigRequest;
import com.example.starter.plan.web.dto.HazardSeparationRuleRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.SectionOpenListRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 危险品隔离并发裁决测试（真实 H2 内存库）：
 * 同一计划并发发布最多一次成功；配置写入与发布经全局锁按事务提交顺序串行；
 * 同一批量 requestId 的并发提交只产生一次结果。
 */
@SpringBootTest
@Import(ControlledTimeTestConfig.class)
class HazardConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService service;
    @Autowired
    private HazardConfigService configService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearHazardConfig() {
        jdbc.update("DELETE FROM hazard_class_separation");
        jdbc.update("DELETE FROM hazard_section_open_class");
        jdbc.update("DELETE FROM hazard_restricted_section");
        jdbc.update("DELETE FROM hazard_config_version");
        jdbc.update("DELETE FROM idempotency_record WHERE op_type = 'SECTION_CONFIG'");
    }

    @Test
    void concurrentPublishSamePlanAtMostOneSucceeds() throws Exception {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, section, 8, 9);
        int threads = 4;

        List<Outcome> outcomes = runConcurrently(buildPublishTasks(scheduleKey, threads));

        long ok = outcomes.stream().filter(o -> o == Outcome.OK).count();
        long conflict = outcomes.stream().filter(o -> o == Outcome.CONFLICT).count();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        assertThat(service.getPlan(scheduleKey).status()).isEqualTo("PUBLISHED");
        // 发布快照恰好一行
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_publish_snapshot s"
                        + " JOIN rail_day_plan p ON p.id = s.plan_id"
                        + " WHERE p.schedule_key = ?", Integer.class, scheduleKey);
        assertThat(snapshots).isEqualTo(1);
    }

    @Test
    void concurrentConfigWritesDoNotOverlapRanges() throws Exception {
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    // 四个线程都写同一固定生效区间，至多一个成功，其余 409
                    configService.writeConfig(new HazardConfigRequest(
                            key("REQ"), "op",
                            Instant.parse("2050-01-01T00:00:00Z"),
                            Instant.parse("2051-01-01T00:00:00Z"),
                            List.of(), List.of()));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    return Outcome.CONFLICT;
                }
            }));
        }
        ready.await();
        go.countDown();
        long ok = 0;
        long conflict = 0;
        for (Future<Outcome> f : futures) {
            Outcome o = f.get(30, TimeUnit.SECONDS);
            if (o == Outcome.OK) {
                ok++;
            } else {
                conflict++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
    }

    @Test
    void concurrentBatchRescheduleSameRequestKeyAppliesOnce() throws Exception {
        String section = key("SEC");
        String oldKey = key("SCH");
        String newKey = key("SCH");
        createDraft(oldKey, section, 8, 9);
        service.publish(oldKey, key("REQ"));
        createDraft(newKey, section, 9, 10);

        String requestKey = key("BREQ");
        BatchRescheduleRequest request = new BatchRescheduleRequest(requestKey, null,
                List.of(new BatchRescheduleItemRequest(oldKey, newKey, 1, 1)));
        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    service.batchReschedule(request);
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    return Outcome.CONFLICT;
                }
            }));
        }
        ready.await();
        go.countDown();
        long ok = 0;
        for (Future<Outcome> f : futures) {
            if (f.get(30, TimeUnit.SECONDS) == Outcome.OK) {
                ok++;
            }
        }
        pool.shutdown();

        // 同 requestId 同参并发重放：全部返回首次成功结果
        assertThat(ok).isEqualTo(threads);
        assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
        assertThat(service.getPlan(newKey).status()).isEqualTo("PUBLISHED");
        Integer links = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_reschedule_link l"
                        + " JOIN rail_day_plan p ON p.id = l.predecessor_plan_id"
                        + " WHERE p.schedule_key = ?", Integer.class, oldKey);
        assertThat(links).isEqualTo(1);
    }

    @Test
    void publishReplayWithDifferentOperatorIsConflict() {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, section, 8, 9);
        String requestKey = key("REQ");
        service.publish(scheduleKey, requestKey, "alice");
        try {
            service.publish(scheduleKey, requestKey, "bob");
            org.assertj.core.api.Assertions.fail("操作者不同应判 409");
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("IDEMPOTENT_KEY_REUSED");
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private List<Callable<Outcome>> buildPublishTasks(String scheduleKey, int threads) {
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    service.publish(scheduleKey, key("REQ"));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    return Outcome.CONFLICT;
                }
            });
        }
        return tasks;
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

    private void createDraft(String scheduleKey, String section, int startHour, int endHour) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour), at(endHour)))));
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
