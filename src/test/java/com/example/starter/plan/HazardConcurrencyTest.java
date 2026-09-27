package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.model.PublishSnapshot;
import com.example.starter.plan.repo.HazardRepository;
import com.example.starter.plan.service.HazardService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.ConfigureSectionRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.HazardCarRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.UpdateHazardCarsRequest;
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
 * 危险品能力并发测试：同计划并发发布最多一次成功且快照唯一、
 * 并发区段配置按提交顺序串行且版本单调、编组修改与发布并发按提交顺序生效。
 */
@SpringBootTest
class HazardConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private HazardService hazardService;

    @Autowired
    private HazardRepository hazardRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishSamePlanAtMostOneSucceedsWithSingleSnapshot() throws Exception {
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"));
        hazardService.replaceHazardCars(scheduleKey, new UpdateHazardCarsRequest(
                key("REQ"), "op-1", 1, List.of(new HazardCarRequest(1, "EXPLOSIVE"))));

        int threads = 4;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    planService.publish(scheduleKey, key("REQ"), "op-1");
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(threads - 1);
        // 快照恰好一条
        List<PublishSnapshot> snapshots = hazardRepo.findSnapshotsByPlanId(planIdOf(scheduleKey));
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).validationResult()).isEqualTo("PASSED");
    }

    @Test
    void concurrentSectionConfigSerializedWithMonotonicVersion() throws Exception {
        int before = hazardRepo.findConfigVersions().sectionVersion();
        int threads = 4;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String section = key("SEC");
            tasks.add(() -> {
                hazardService.configureSection(section, new ConfigureSectionRequest(
                        key("REQ"), "op-1", List.of("FLAMMABLE")));
                return Outcome.OK;
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes).allMatch(o -> o == Outcome.OK);
        // 每次成功配置版本恰好加一，并发下不丢更新
        assertThat(hazardRepo.findConfigVersions().sectionVersion()).isEqualTo(before + threads);
    }

    @Test
    void concurrentHazardCarUpdateAndPublishApplyInCommitOrder() throws Exception {
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        hazardService.replaceHazardCars(scheduleKey, new UpdateHazardCarsRequest(
                                key("REQ"), "op-1", 1,
                                List.of(new HazardCarRequest(1, "EXPLOSIVE"))));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 发布先提交时编组修改应得 409 状态冲突
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    planService.publish(scheduleKey, key("REQ"), "op-1");
                    return Outcome.OK;
                }));

        // 发布必须成功；编组修改要么先于发布成功（版本 2，快照含编组），要么 409（版本 1）
        PlanResponse plan = planService.getPlan(scheduleKey);
        assertThat(plan.status()).isEqualTo("PUBLISHED");
        List<PublishSnapshot> snapshots = hazardRepo.findSnapshotsByPlanId(planIdOf(scheduleKey));
        assertThat(snapshots).hasSize(1);
        if (outcomes.get(0) == Outcome.OK) {
            assertThat(plan.version()).isEqualTo(2);
            assertThat(snapshots.get(0).formationJson()).contains("EXPLOSIVE");
        } else {
            assertThat(plan.version()).isEqualTo(1);
            assertThat(snapshots.get(0).formationJson()).isEqualTo("[]");
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private void createDraft(String scheduleKey, String section) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(8), at(9))), null));
    }

    private long planIdOf(String scheduleKey) {
        return jdbc.queryForObject("SELECT id FROM rail_day_plan WHERE schedule_key = ?",
                Long.class, scheduleKey);
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
