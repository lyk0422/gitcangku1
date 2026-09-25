package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.model.CrewRole;
import com.example.starter.plan.repo.CrewRepository;
import com.example.starter.plan.service.CrewService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.CreateQualificationRequest;
import com.example.starter.plan.web.dto.CrewAssignmentRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PublishPlanRequest;
import com.example.starter.plan.web.dto.QualificationView;
import com.example.starter.plan.web.dto.TerminateQualificationRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * 乘务资质并发裁决测试（H2 内存库）：提前终止与发布竞争按事务提交顺序生效；
 * 并发终止同一资质最多一次成功且风险记录恰好一份；同键并发终止重放首次结果。
 */
@SpringBootTest
class CrewQualificationConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final Instant EXPIRES = Instant.parse("2026-12-31T16:00:00Z");

    @Autowired
    private PlanService planService;

    @Autowired
    private CrewService crewService;

    @Autowired
    private CrewRepository crewRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentTerminateAndPublishDecidedByCommitOrder() throws Exception {
        String section = key("SEC");
        String driver = key("CREW");
        String conductor = key("CREW");
        String driverQual = key("QUAL");
        String conductorQual = key("QUAL");
        createQual(driverQual, driver, section);
        createQual(conductorQual, conductor, section);
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, section);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        crewService.terminateQualification(driverQual,
                                new TerminateQualificationRequest(key("REQ"), 1, "op"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        planService.publish(scheduleKey, new PublishPlanRequest(key("REQ"), "op",
                                new CrewAssignmentRequest(driver, driverQual),
                                new CrewAssignmentRequest(conductor, conductorQual)));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        // 终止必须成功；发布按提交顺序裁决
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        QualificationView qual = crewService.getQualification(driverQual);
        assertThat(qual.terminated()).isTrue();
        long planId = planId(scheduleKey);
        if (outcomes.get(1) == Outcome.OK) {
            // 发布先提交：计划已发布，终止回查命中并写入风险记录
            assertThat(planService.getPlan(scheduleKey).status()).isEqualTo("PUBLISHED");
            assertThat(crewRepo.findRiskRecordsByPlan(planId)).hasSize(1);
            assertThat(crewRepo.findRiskRecordsByPlan(planId).get(0).role())
                    .isEqualTo(CrewRole.DRIVER);
        } else {
            // 终止先提交：发布 422 回滚，计划保持草稿、无乘务快照、无风险记录
            assertThat(planService.getPlan(scheduleKey).status()).isEqualTo("DRAFT");
            assertThat(crewRepo.findPlanCrew(planId)).isEmpty();
            assertThat(crewRepo.findRiskRecordsByPlan(planId)).isEmpty();
        }
    }

    @Test
    void concurrentTerminateSameQualOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        String driver = key("CREW");
        String conductor = key("CREW");
        String driverQual = key("QUAL");
        String conductorQual = key("QUAL");
        createQual(driverQual, driver, section);
        createQual(conductorQual, conductor, section);
        String scheduleKey = key("SCH");
        createDraft(scheduleKey, section);
        planService.publish(scheduleKey, new PublishPlanRequest(key("REQ"), "op",
                new CrewAssignmentRequest(driver, driverQual),
                new CrewAssignmentRequest(conductor, conductorQual)));

        int threads = 3;
        List<Callable<Outcome>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    crewService.terminateQualification(driverQual,
                            new TerminateQualificationRequest(key("REQ"), 1, "op"));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);

        // 恰好一次终止成功，其余 409 状态冲突
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(threads - 1);

        // 风险记录恰好一份（唯一约束兜底），资质版本只加一
        long planId = planId(scheduleKey);
        assertThat(crewRepo.findRiskRecordsByPlan(planId)).hasSize(1);
        QualificationView qual = crewService.getQualification(driverQual);
        assertThat(qual.terminated()).isTrue();
        assertThat(qual.version()).isEqualTo(2);
    }

    @Test
    void concurrentTerminateSameRequestKeyReplaysFirstResult() throws Exception {
        String driverQual = key("QUAL");
        createQual(driverQual, key("CREW"), key("SEC"));
        String requestKey = key("REQ");
        int threads = 3;

        List<Callable<QualificationView>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> crewService.terminateQualification(driverQual,
                    new TerminateQualificationRequest(requestKey, 1, "op")));
        }
        List<QualificationView> results = runConcurrentlyValues(tasks);

        // 同键同参并发：全部返回首次结果，资质只终止一次
        assertThat(results).allMatch(r -> r.equals(results.get(0)));
        assertThat(results.get(0).terminated()).isTrue();
        assertThat(crewService.getQualification(driverQual).version()).isEqualTo(2);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private void createQual(String qualCode, String crewId, String section) {
        crewService.createQualification(new CreateQualificationRequest(
                key("REQ"), qualCode, crewId, List.of(section), EXPIRES));
    }

    private void createDraft(String scheduleKey, String section) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section,
                        DAY.atTime(8, 0).atZone(SH).toInstant(),
                        DAY.atTime(9, 0).atZone(SH).toInstant()))));
    }

    private long planId(String scheduleKey) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM rail_day_plan WHERE schedule_key = ?", Long.class, scheduleKey);
        return id == null ? -1L : id;
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
        List<Outcome> outcomes = new java.util.ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private List<QualificationView> runConcurrentlyValues(List<Callable<QualificationView>> tasks)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<QualificationView>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<QualificationView> results = new java.util.ArrayList<>();
        for (Future<QualificationView> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
