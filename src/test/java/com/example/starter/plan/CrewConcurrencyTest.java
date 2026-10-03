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
 * 乘务资格门禁并发测试（H2 内存库）：同一成员并发发布互斥、同 requestId 并发重放、
 * 资格撤销与发布并发时不得基于已失效资格发布。
 */
@SpringBootTest
class CrewConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private CrewService crewService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishSameCrewOnlyOneSucceeds() throws Exception {
        String member = key("M");
        grant(member, iso(0), iso(23, 59));
        int contenders = 4;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String scheduleKey = key("SCH");
            // 各自独立区段，仅乘务占用窗口重叠
            createDraft(scheduleKey, key("SEC"), 10, 11);
            scheduleKeys.add(scheduleKey);
        }

        List<Outcome> outcomes = runConcurrently(scheduleKeys.stream()
                .<Callable<Outcome>>map(sk -> () -> {
                    try {
                        planService.publish(sk, key("REQ"), List.of(member));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("CREW_QUALIFICATION_DENIED");
                        return Outcome.CONFLICT;
                    }
                }).toList());

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);
        // 最终该成员只在一张已发布计划上占用
        Integer occupied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM published_crew_assignment a"
                        + " JOIN rail_day_plan p ON p.id = a.plan_id"
                        + " WHERE a.crew_member_id = ? AND p.status = 'PUBLISHED'",
                Integer.class, member);
        assertThat(occupied).isEqualTo(1);
    }

    @Test
    void concurrentPublishSameRequestIdReplaysFirstAssignment() throws Exception {
        String member = key("M");
        grant(member, iso(0), iso(23, 59));
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, key("SEC"), 10, 11);
        String requestKey = key("REQ");
        int threads = 4;

        List<Callable<PlanResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> planService.publish(scheduleKey, requestKey, List.of(member)));
        }
        List<PlanResponse> responses = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PlanResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        for (Future<PlanResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次人员与时隙分配，快照只写入一次
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).status()).isEqualTo("PUBLISHED");
        assertThat(responses.get(0).crewMembers()).containsExactly(member);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM published_crew_assignment a"
                        + " JOIN rail_day_plan p ON p.id = a.plan_id"
                        + " WHERE p.schedule_key = ?",
                Integer.class, scheduleKey);
        assertThat(snapshots).isEqualTo(1);
    }

    @Test
    void revokeAndPublishRaceNeverPublishesRevokedVersion() throws Exception {
        for (int round = 0; round < 8; round++) {
            String member = key("M");
            grant(member, iso(0), iso(23, 59));
            String scheduleKey = key("SCH");
            createDraft(scheduleKey, key("SEC"), 10, 11);

            List<Outcome> outcomes = runConcurrently(List.of(
                    () -> {
                        try {
                            planService.publish(scheduleKey, key("REQ"), List.of(member));
                            return Outcome.OK;
                        } catch (ApiException e) {
                            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                            assertThat(e.code()).isEqualTo("CREW_QUALIFICATION_DENIED");
                            return Outcome.CONFLICT;
                        }
                    },
                    () -> {
                        crewService.revoke(member, key("REQ"));
                        return Outcome.OK;
                    }));

            // 撤销必须成功，资格最终为 REVOKED 且版本 2
            assertThat(outcomes.get(1)).isEqualTo(Outcome.OK);
            var qual = jdbc.queryForMap(
                    "SELECT version, status FROM crew_qualification WHERE crew_member_id = ?",
                    member);
            assertThat(qual.get("status")).isEqualTo("REVOKED");
            assertThat(((Number) qual.get("version")).intValue()).isEqualTo(2);

            PlanResponse plan = planService.getPlan(scheduleKey);
            List<Integer> snapshotVersions = jdbc.queryForList(
                    "SELECT a.qualification_version FROM published_crew_assignment a"
                            + " JOIN rail_day_plan p ON p.id = a.plan_id"
                            + " WHERE p.schedule_key = ?",
                    Integer.class, scheduleKey);
            if (outcomes.get(0) == Outcome.OK) {
                // 发布先串行生效：快照保留发布时的原版本 1，之后撤销才加一
                assertThat(plan.status()).isEqualTo("PUBLISHED");
                assertThat(snapshotVersions).containsExactly(1);
            } else {
                // 撤销先串行生效：发布基于已失效资格被拒绝，整单无快照
                assertThat(plan.status()).isEqualTo("DRAFT");
                assertThat(snapshotVersions).isEmpty();
            }
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

    private void grant(String member, String fromIso, String toIso) {
        crewService.grant(new GrantQualificationRequest(key("REQ"), member, 1,
                Instant.parse(fromIso), Instant.parse(toIso)));
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

    private static String iso(int hour) {
        return iso(hour, 0);
    }

    private static String iso(int hour, int minute) {
        return at(hour, minute).toString();
    }

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}