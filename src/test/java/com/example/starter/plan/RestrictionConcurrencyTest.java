package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.RestrictionService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RestrictionCreateRequest;
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
 * 限速场景下的并发与事务边界：同一计划的并发发布/改签不产生两套生效时刻，
 * 限速令并发登记按事务提交顺序裁决，唯一约束在真实 H2 上生效。
 */
@SpringBootTest
class RestrictionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2099, 1, 1);

    @Autowired
    private PlanService planService;

    @Autowired
    private RestrictionService restrictionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishSamePlanProducesSingleEffectiveSchedule() throws Exception {
        String section = key("SEC");
        createRestriction(key("RST"), section, 8, 0, 8, 10, 100);
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, section, 8, 9);
        String requestKey = key("REQ");

        int threads = 4;
        List<Outcome> outcomes = runConcurrently(java.util.Collections.nCopies(threads,
                (Callable<Outcome>) () -> {
                    try {
                        planService.publish(scheduleKey, requestKey, "op-race");
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 同键并发：要么重放首次结果，要么因已发布而 409，二者都可接受
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes).contains(Outcome.OK);
        PlanResponse plan = planService.getPlan(scheduleKey);
        assertThat(plan.status()).isEqualTo("PUBLISHED");
        // 只有一套生效时刻：整体顺延 10 分钟，重排记录恰好一条
        assertThat(plan.occupancies().get(0).startUtc()).isEqualTo(at(8, 10));
        Integer rearranges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_rearrange WHERE schedule_key = ?",
                Integer.class, scheduleKey);
        assertThat(rearranges).isEqualTo(1);
    }

    @Test
    void concurrentRescheduleSameOldPlanOnlyOneSucceeds() throws Exception {
        String sectionOld = key("SEC");
        String oldKey = key("SCH");
        createDraft(oldKey, sectionOld, 12, 13);
        planService.publish(oldKey, key("REQ"));

        String sectionA = key("SEC");
        String sectionB = key("SEC");
        createRestriction(key("RST"), sectionA, 8, 0, 8, 10, 100);
        createRestriction(key("RST"), sectionB, 8, 0, 8, 10, 100);
        String newA = key("SCH");
        String newB = key("SCH");
        createDraft(newA, sectionA, 8, 9);
        createDraft(newB, sectionB, 8, 9);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.reschedule(oldKey,
                                new RescheduleRequest(key("REQ"), newA, 1, 1, "op-a"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        planService.reschedule(oldKey,
                                new RescheduleRequest(key("REQ"), newB, 1, 1, "op-b"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(planService.getPlan(oldKey).status()).isEqualTo("CANCELLED");
        // 恰好一个新计划发布，且为顺延后的唯一一套生效时刻
        int published = 0;
        for (String newKey : List.of(newA, newB)) {
            PlanResponse plan = planService.getPlan(newKey);
            if ("PUBLISHED".equals(plan.status())) {
                published++;
                assertThat(plan.occupancies().get(0).startUtc()).isEqualTo(at(8, 10));
            } else {
                assertThat(plan.status()).isEqualTo("DRAFT");
                assertThat(plan.occupancies().get(0).startUtc()).isEqualTo(at(8, 0));
            }
        }
        assertThat(published).isEqualTo(1);
    }

    @Test
    void concurrentRestrictionCreateSameKeyOnlyOnePersists() throws Exception {
        String restrictionKey = key("RST");
        String section = key("SEC");
        int threads = 4;
        List<Outcome> outcomes = runConcurrently(java.util.Collections.nCopies(threads,
                (Callable<Outcome>) () -> {
                    try {
                        restrictionService.create(new RestrictionCreateRequest(
                                key("REQ"), restrictionKey, section,
                                at(8, 0), at(9, 0), 100, "op-race"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_speed_restriction WHERE restriction_key = ?",
                Integer.class, restrictionKey);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void publishAndRestrictionRegistrationAreSerializedByCommitOrder() throws Exception {
        String section = key("SEC");
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, section, 8, 9);
        String restrictionKey = key("RST");

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    restrictionService.create(new RestrictionCreateRequest(
                            key("REQ"), restrictionKey, section,
                            at(8, 0), at(8, 10), 100, "op-reg"));
                    return Outcome.OK;
                },
                () -> {
                    planService.publish(scheduleKey, key("REQ"));
                    return Outcome.OK;
                }));

        assertThat(outcomes).containsOnly(Outcome.OK);
        PlanResponse plan = planService.getPlan(scheduleKey);
        assertThat(plan.status()).isEqualTo("PUBLISHED");
        // 按事务提交顺序裁决：限速令先提交则顺延 10 分钟，否则按原时刻发布；
        // 两种结果都必须自洽（生效时刻与重排记录一一对应）
        Integer rearranges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_rearrange WHERE schedule_key = ?",
                Integer.class, scheduleKey);
        if (rearranges == 1) {
            assertThat(plan.occupancies().get(0).startUtc()).isEqualTo(at(8, 10));
        } else {
            assertThat(rearranges).isZero();
            assertThat(plan.occupancies().get(0).startUtc()).isEqualTo(at(8, 0));
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
                        at(startHour, 0), at(endHour, 0)))));
    }

    private void createRestriction(String restrictionKey, String section,
                                   int startHour, int startMinute, int endHour, int endMinute,
                                   int speed) {
        restrictionService.create(new RestrictionCreateRequest(key("REQ"), restrictionKey,
                section, at(startHour, startMinute), at(endHour, endMinute), speed, "op-test"));
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

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}