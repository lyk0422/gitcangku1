package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.SectionRegisterRequest;
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
 * 抢占并发与幂等竞态测试（真实 H2 数据库，MODE=MySQL）：
 * 两个高等级草稿并发抢占同一低等级计划最多一个成功，另一个 409；
 * 抢占与取消并发按事务提交顺序裁决，不出现中间不一致状态。
 */
@SpringBootTest
class PreemptionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPreemptSamePlanOnlyOneSucceeds() throws Exception {
        String secShared = key("SEC");
        String secHigh = key("SEC");
        service.registerSection(secHigh, new SectionRegisterRequest(5));

        String low = key("SCH");
        createDraft(low, occ("G1", secShared, 8, 9));
        service.publish(low, key("REQ"), null);

        // 两个高等级草稿（等级 5）同时抢占同一低等级计划
        String draftA = key("SCH");
        String draftB = key("SCH");
        createDraft(draftA, occ("G2", secShared, 8, 9), occ("G3", secHigh, 10, 11));
        createDraft(draftB, occ("G4", secShared, 8, 9), occ("G5", secHigh, 12, 13));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publishOutcome(draftA, key("PRE")),
                () -> publishOutcome(draftB, key("PRE"))));

        // 最多一个成功，另一个必须 409（同一时隙只能被抢占一次）
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT_409).count()).isEqualTo(1);

        // 被抢占计划进入终态，恰好一个草稿发布成功，另一个保持草稿
        assertThat(service.getPlan(low).status()).isEqualTo("PREEMPTED");
        String statusA = service.getPlan(draftA).status();
        String statusB = service.getPlan(draftB).status();
        assertThat(List.of(statusA, statusB)).containsExactlyInAnyOrder("PUBLISHED", "DRAFT");

        // 恰好一条抢占记录，且区段上只有一个生效时隙（不属于被抢占计划）
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_preemption WHERE loser_schedule_key = ?",
                Integer.class, low);
        assertThat(records).isEqualTo(1);
        assertThat(service.getPublishedSlots(DAY, secShared)).hasSize(1);
        assertThat(service.getPublishedSlots(DAY, secShared).get(0).scheduleKey()).isNotEqualTo(low);
    }

    @Test
    void concurrentPreemptAndCancelAdjudicatedByCommitOrder() throws Exception {
        String secShared = key("SEC");
        String secHigh = key("SEC");
        service.registerSection(secHigh, new SectionRegisterRequest(5));

        String low = key("SCH");
        createDraft(low, occ("G1", secShared, 8, 9));
        service.publish(low, key("REQ"), null);

        String draft = key("SCH");
        createDraft(draft, occ("G2", secShared, 8, 9), occ("G3", secHigh, 10, 11));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publishOutcome(draft, key("PRE")),
                () -> {
                    try {
                        service.cancel(low, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT_409;
                    }
                }));

        String lowStatus = service.getPlan(low).status();
        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_preemption WHERE loser_schedule_key = ?",
                Integer.class, low);
        // 按提交顺序裁决：低计划要么被抢占要么被取消，绝不仍处于已发布
        assertThat(lowStatus).isIn("PREEMPTED", "CANCELLED");
        if ("PREEMPTED".equals(lowStatus)) {
            // 抢占先提交：记录存在，草稿发布成功，取消必然 409
            assertThat(records).isEqualTo(1);
            assertThat(service.getPlan(draft).status()).isEqualTo("PUBLISHED");
            assertThat(outcomes.get(1)).isEqualTo(Outcome.CONFLICT_409);
        } else {
            // 取消先提交：无抢占记录；草稿随后无冲突正常发布，或因目标状态已变而 409
            assertThat(records).isEqualTo(0);
            assertThat(outcomes.get(1)).isEqualTo(Outcome.OK);
        }
        // 任何情况下低计划的时隙都不再生效
        assertThat(service.getPublishedSlots(DAY, secShared))
                .allMatch(s -> !s.scheduleKey().equals(low));
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT_409
    }

    private Outcome publishOutcome(String scheduleKey, String preemptKey) {
        try {
            service.publish(scheduleKey, key("REQ"), preemptKey);
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return Outcome.CONFLICT_409;
        }
    }

    private void createDraft(String scheduleKey, OccupancyRequest... occupancies) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(occupancies)));
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

    private static OccupancyRequest occ(String trainNo, String sectionId, int startHour,
                                        int endHour) {
        return new OccupancyRequest(trainNo, sectionId, at(startHour), at(endHour));
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
