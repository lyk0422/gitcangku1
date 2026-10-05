package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.RestrictionService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RestrictionActionRequest;
import com.example.starter.plan.web.dto.RestrictionRegisterRequest;
import com.example.starter.plan.web.dto.RestrictionReviseRequest;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import java.time.Clock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 限速场景并发测试：并发改签与发布重排只产生一套生效时刻、
 * 同键并发登记限速令仅一条成功、撤销与修订按事务提交顺序裁决。
 */
@SpringBootTest
class RearrangeConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final Instant FIXED_NOW = Instant.parse("2026-09-20T00:00:00Z");

    @Autowired
    private PlanService planService;

    @Autowired
    private RestrictionService restrictionService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(FIXED_NOW);
        when(clock.millis()).thenReturn(FIXED_NOW.toEpochMilli());
    }

    @Test
    void concurrentRescheduleAndPublishYieldSingleEffectiveSchedule() throws Exception {
        String section = key("SEC");
        restrictionService.registerSection(new SectionProfileRequest(section, 200, 60));
        // 旧计划在限速登记前发布
        String oldKey = key("SCH");
        createDraft(oldKey, section, 8, 9);
        planService.publish(oldKey, key("REQ"));
        restrictionService.register(new RestrictionRegisterRequest(
                key("REQ"), key("RST"), section, at(8), at(9), 100, null));
        String newKey = key("SCH");
        createDraft(newKey, section, 8, 9);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.reschedule(oldKey, new RescheduleRequest(key("REQ"), newKey, 1, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        planService.publish(newKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        // 恰有一个操作成功，新计划只有一套生效时刻（顺延一次）与一条重排记录
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        PlanResponse newPlan = planService.getPlan(newKey);
        assertThat(newPlan.status()).isEqualTo("PUBLISHED");
        assertThat(newPlan.occupancies().get(0).startUtc()).isEqualTo(at(9));
        assertThat(planService.getRearrangements(newKey)).hasSize(1);
        // 旧计划状态与胜出操作一致：改签胜则已取消，发布胜则仍发布
        String oldStatus = planService.getPlan(oldKey).status();
        if (outcomes.get(0) == Outcome.OK) {
            assertThat(oldStatus).isEqualTo("CANCELLED");
        } else {
            assertThat(oldStatus).isEqualTo("PUBLISHED");
        }
    }

    @Test
    void concurrentRegisterSameRestrictionKeyOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        restrictionService.registerSection(new SectionProfileRequest(section, 200, 30));
        String restrictionKey = key("RST");
        int threads = 4;

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    restrictionService.register(new RestrictionRegisterRequest(
                            key("REQ"), restrictionKey, section, at(8), at(9), 100, null));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_speed_restriction WHERE restriction_key = ?",
                Integer.class, restrictionKey);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentRevokeAndReviseApplyInCommitOrder() throws Exception {
        String section = key("SEC");
        restrictionService.registerSection(new SectionProfileRequest(section, 200, 30));
        String restrictionKey = key("RST");
        restrictionService.register(new RestrictionRegisterRequest(
                key("REQ"), restrictionKey, section, at(8), at(9), 100, null));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    restrictionService.revoke(restrictionKey,
                            new RestrictionActionRequest(key("REQ"), null));
                    return Outcome.OK;
                },
                () -> {
                    try {
                        restrictionService.revise(restrictionKey,
                                new RestrictionReviseRequest(key("REQ"), at(9), at(10), 80, null));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        // 撤销必然成功；修订若先于撤销提交则成功（版本 2），否则 409（版本 1）
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        var restriction = restrictionService.getRestriction(restrictionKey);
        assertThat(restriction.status()).isEqualTo("REVOKED");
        if (outcomes.get(1) == Outcome.OK) {
            assertThat(restriction.version()).isEqualTo(2);
        } else {
            assertThat(restriction.version()).isEqualTo(1);
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private void createDraft(String scheduleKey, String section, int startHour, int endHour) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        at(startHour), at(endHour)))));
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
