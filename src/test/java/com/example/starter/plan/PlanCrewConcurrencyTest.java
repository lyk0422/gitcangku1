package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.CrewService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.GrantQualificationRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import java.time.Instant;
import java.time.LocalDate;
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
 * 乘务门禁并发裁决测试（真实 H2 内存库）：
 * 同一乘务员重叠窗口并发发布恰好一单成功；发布与资格撤销在资格行上以 FOR UPDATE 串行，
 * 不会出现基于已失效资格的发布；同 requestId 并发重放只产生首次人员分配。
 */
@SpringBootTest
class PlanCrewConcurrencyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final Instant T08 = Instant.parse("2026-09-22T08:00:00Z");
    private static final Instant T10 = Instant.parse("2026-09-22T10:00:00Z");
    private static final Instant T11 = Instant.parse("2026-09-22T11:00:00Z");
    private static final Instant T12 = Instant.parse("2026-09-22T12:00:00Z");

    @Autowired
    private PlanService planService;

    @Autowired
    private CrewService crewService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishSameCrewOverlappingWindowOnlyOneSucceeds() throws Exception {
        String line = key("LINE");
        String member = key("M");
        String sectionA = key("SEC-A");
        String sectionB = key("SEC-B");
        crewService.registerSection(sectionA, line, 1);
        crewService.registerSection(sectionB, line, 1);
        crewService.registerMember(member, "甲");
        crewService.grantQualification(member,
                new GrantQualificationRequest(line, 1, T08, T12));

        // 区段不同（排除时隙互斥），乘务组同为甲且窗口完全重叠
        String planA = key("SCH");
        String planB = key("SCH");
        createDraft(planA, sectionA);
        createDraft(planB, sectionB);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> publish(planA, member),
                () -> publish(planB, member)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);

        // 最终只有一张计划发布且乘务快照只有一份
        List<String> statuses = List.of(planService.getPlan(planA).status(),
                planService.getPlan(planB).status());
        assertThat(statuses).containsExactlyInAnyOrder("PUBLISHED", "DRAFT");
        Integer crewRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_crew_member cm"
                        + " JOIN rail_day_plan p ON p.id = cm.plan_id"
                        + " WHERE p.schedule_key IN (?, ?)",
                Integer.class, planA, planB);
        assertThat(crewRows).isEqualTo(1);

        // 失败方无快照
        String loser = statuses.get(0).equals("DRAFT") ? planA : planB;
        assertThat(planService.getPlan(loser).crew()).isNull();
    }

    @Test
    void publishAndRevocationRaceNeverPublishesInvalidQualification() throws Exception {
        // 多次迭代以覆盖两种提交顺序（撤销先/发布先）
        for (int iteration = 0; iteration < 5; iteration++) {
            String line = key("LINE");
            String section = key("SEC");
            String member = key("M");
            crewService.registerSection(section, line, 1);
            crewService.registerMember(member, "甲");
            crewService.grantQualification(member,
                    new GrantQualificationRequest(line, 1, T08, T12));
            String plan = key("SCH");
            createDraft(plan, section);

            List<Outcome> outcomes = runConcurrently(List.of(
                    () -> publish(plan, member),
                    () -> {
                        crewService.revokeQualification(member, line, 1);
                        return Outcome.OK;
                    }));

            boolean publishOk = outcomes.get(0) == Outcome.OK;
            PlanResponse finalPlan = planService.getPlan(plan);
            String qualStatus = jdbc.queryForObject(
                    "SELECT q.status FROM rail_crew_qualification q"
                            + " JOIN rail_crew_member m ON m.id = q.member_id"
                            + " WHERE m.member_key = ? AND q.line_id = ?",
                    String.class, member, line);

            if (publishOk) {
                // 发布先提交：发布依据的资格当时仍 ACTIVE，计划保留 v1 快照；撤销随后生效
                assertThat(finalPlan.status()).isEqualTo("PUBLISHED");
                assertThat(finalPlan.crew()).isNotNull();
                assertThat(finalPlan.crew().members()).hasSize(1);
                assertThat(finalPlan.crew().members().get(0).qualificationVersion()).isEqualTo(1);
                assertThat(qualStatus).isEqualTo("REVOKED");
            } else {
                // 撤销先提交：发布必须 422 CREW_GATE，计划保持草稿，无任何人员/站台占用
                assertThat(finalPlan.status()).isEqualTo("DRAFT");
                assertThat(finalPlan.crew()).isNull();
                assertThat(qualStatus).isEqualTo("REVOKED");
            }

            // 无论哪种顺序，都不能再有第二张计划基于该（已撤销）资格发布。
            // 用不同区段（同线路、同人员、同窗口）排除时隙因素，只验证资格门禁。
            String followerSection = key("SEC-FOLLOW");
            crewService.registerSection(followerSection, line, 1);
            String follower = key("SCH");
            planService.createDraft(new CreatePlanRequest(key("REQ"), follower, DAY,
                    List.of(new OccupancyRequest("G-" + UUID.randomUUID(), followerSection,
                            T10, T11))));
            try {
                planService.publishWithCrew(follower, key("REQ"), List.of(member));
                org.assertj.core.api.Assertions.fail("迭代 " + iteration + " 已撤销资格不应再发布成功");
            } catch (ApiException e) {
                assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.code()).isEqualTo("CREW_GATE");
            }
            assertThat(planService.getPlan(follower).status()).isEqualTo("DRAFT");
        }
    }

    @Test
    void concurrentSameRequestReplayAllocatesCrewOnce() throws Exception {
        String line = key("LINE");
        String section = key("SEC");
        String member = key("M");
        crewService.registerSection(section, line, 1);
        crewService.registerMember(member, "甲");
        crewService.grantQualification(member,
                new GrantQualificationRequest(line, 1, T08, T12));
        String plan = key("SCH");
        createDraft(plan, section);
        String requestKey = key("REQ");
        int threads = 4;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PlanResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return planService.publishWithCrew(plan, requestKey, List.of(member));
            }));
        }
        ready.await();
        go.countDown();
        List<PlanResponse> responses = new ArrayList<>();
        for (Future<PlanResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).status()).isEqualTo("PUBLISHED");
        Integer crewRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_crew_member cm"
                        + " JOIN rail_day_plan p ON p.id = cm.plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, plan);
        Integer idemRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE request_key = ?",
                Integer.class, requestKey);
        assertThat(crewRows).isEqualTo(1);
        assertThat(idemRows).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private void createDraft(String scheduleKey, String section) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section, T10, T11))));
    }

    private Outcome publish(String scheduleKey, String member) {
        try {
            planService.publishWithCrew(scheduleKey, key("REQ"), List.of(member));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            return Outcome.CONFLICT;
        }
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

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
