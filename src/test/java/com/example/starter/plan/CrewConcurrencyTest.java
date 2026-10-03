package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.CrewService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.CrewGroupRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.QualificationGrantRequest;
import com.example.starter.plan.web.dto.QualificationRevokeRequest;
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
 * 乘务资格门禁并发测试：资格撤销与发布竞态不得发布基于已失效资格的计划；
 * 同一乘务员并发发布重叠计划仅一个成功。真实 H2 库，协调闩与超时控制。
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
    void concurrentRevokeAndPublishNeverPublishesRevokedQualification() throws Exception {
        String member = key("M");
        String group = key("G");
        crewService.createGroup(new CrewGroupRequest(key("REQ"), group, List.of(member)));
        crewService.grant(new QualificationGrantRequest(key("REQ"), member, 1, at(8), at(20)));
        String scheduleKey = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G1", key("SEC"), at(10), at(11)))));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.publish(scheduleKey, key("REQ"), group);
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("CREW_QUALIFICATION_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    crewService.revoke(new QualificationRevokeRequest(key("REQ"), member));
                    return Outcome.OK;
                }));

        PlanResponse plan = planService.getPlan(scheduleKey);
        int assignments = countAssignments(scheduleKey);
        if (outcomes.get(0) == Outcome.OK) {
            // 发布先于撤销提交：发布时资格仍有效，快照保留当时版本与覆盖结论
            assertThat(plan.status()).isEqualTo("PUBLISHED");
            assertThat(assignments).isEqualTo(1);
            var snapshot = crewService.crewCheck(scheduleKey, null).members().get(0);
            assertThat(snapshot.qualVersion()).isEqualTo(1);
            assertThat(snapshot.covered()).isTrue();
        } else {
            // 撤销先于发布提交：不得发布基于已失效资格的计划，且无任何占用
            assertThat(plan.status()).isEqualTo("DRAFT");
            assertThat(assignments).isZero();
            assertThat(planService.getPublishedSlots(DAY,
                    plan.occupancies().get(0).sectionId())).isEmpty();
            // 撤销已生效：草稿实时核查覆盖失败
            assertThat(crewService.crewCheck(scheduleKey, group).members().get(0).covered())
                    .isFalse();
        }
    }

    @Test
    void concurrentPublishSameMemberOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        String member = key("M");
        String group = key("G");
        crewService.createGroup(new CrewGroupRequest(key("REQ"), group, List.of(member)));
        crewService.grant(new QualificationGrantRequest(key("REQ"), member, 1, at(8), at(20)));

        int contenders = 3;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String scheduleKey = key("SCH");
            // 不同区段规避时隙冲突，仅竞争同一乘务员
            planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                    List.of(new OccupancyRequest("G" + i, section + "-" + i, at(10), at(11)))));
            scheduleKeys.add(scheduleKey);
        }

        List<Outcome> outcomes = runConcurrently(scheduleKeys.stream()
                .<Callable<Outcome>>map(sk -> () -> {
                    try {
                        planService.publish(sk, key("REQ"), group);
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }).toList());

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);

        // 全库该乘务员只有一份生效占用
        Integer occupied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_crew_assignment a JOIN rail_day_plan p"
                        + " ON p.id = a.plan_id WHERE a.member_code = ? AND p.status = 'PUBLISHED'",
                Integer.class, member);
        assertThat(occupied).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
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

    private int countAssignments(String scheduleKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_plan_crew_assignment a JOIN rail_day_plan p"
                        + " ON p.id = a.plan_id WHERE p.schedule_key = ?",
                Integer.class, scheduleKey);
        return count == null ? 0 : count;
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}