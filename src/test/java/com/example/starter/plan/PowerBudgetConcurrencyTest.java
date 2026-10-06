package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.PowerService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BindSectionRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.CreateSubstationRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.SectionBindingResponse;
import com.example.starter.plan.web.dto.SubstationResponse;
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
 * 供电预算并发与竞态测试（真实 H2 事务）：共享变电所并发发布仅一单成功、
 * 同 requestId 并发写重放首次结果且不暴露唯一键错误。
 */
@SpringBootTest
class PowerBudgetConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private PowerService powerService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishSharedSubstationOnlyOneSucceeds() throws Exception {
        String sub = key("SUB");
        powerService.createSubstation(new CreateSubstationRequest(key("RID"), "tester", sub, 100));
        int contenders = 4;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String section = key("SEC");
            powerService.bindSection(section, new BindSectionRequest(key("RID"), "tester", null, sub));
            String scheduleKey = key("SCH");
            planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                    List.of(new OccupancyRequest("G" + i, section, at(8), at(9), 60))));
            scheduleKeys.add(scheduleKey);
        }

        List<Outcome> outcomes = runConcurrently(scheduleKeys.stream()
                .<Callable<Outcome>>map(sk -> () -> {
                    try {
                        planService.publish(sk, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("POWER_BUDGET_EXCEEDED");
                        return Outcome.REJECTED;
                    }
                }).toList());

        // 60 + 60 > 100：仅一单发布成功，其余全部 422
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.REJECTED).count())
                .isEqualTo(contenders - 1);
        Integer published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_day_plan WHERE status = 'PUBLISHED'"
                        + " AND schedule_key IN (" + placeholders(scheduleKeys.size()) + ")",
                Integer.class, scheduleKeys.toArray());
        assertThat(published).isEqualTo(1);
    }

    @Test
    void concurrentCreateSubstationSameRequestIdReturnsFirstResult() throws Exception {
        String sub = key("SUB");
        String requestId = key("RID");
        int threads = 4;
        List<Callable<SubstationResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> powerService.createSubstation(
                    new CreateSubstationRequest(requestId, "tester", sub, 100)));
        }
        List<SubstationResponse> responses = new ArrayList<>();
        for (Future<SubstationResponse> f : submitAll(tasks)) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }

        // 全部返回首次结果；库里只有一条变电所与一条幂等记录，唯一键错误不外泄
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).version()).isEqualTo(1);
        Integer subCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_substation WHERE substation_key = ?",
                Integer.class, sub);
        assertThat(subCount).isEqualTo(1);
        Integer recordCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM write_request_record WHERE request_id = ?",
                Integer.class, requestId);
        assertThat(recordCount).isEqualTo(1);
    }

    @Test
    void concurrentBindSameSectionSameRequestIdReturnsFirstResult() throws Exception {
        String sub = key("SUB");
        powerService.createSubstation(new CreateSubstationRequest(key("RID"), "tester", sub, 100));
        String section = key("SEC");
        String requestId = key("RID");
        int threads = 4;
        List<Callable<SectionBindingResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> powerService.bindSection(section,
                    new BindSectionRequest(requestId, "tester", null, sub)));
        }
        List<SectionBindingResponse> responses = new ArrayList<>();
        for (Future<SectionBindingResponse> f : submitAll(tasks)) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }

        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).version()).isEqualTo(1);
        Integer bindingCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_section_binding WHERE section_id = ?",
                Integer.class, section);
        assertThat(bindingCount).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        REJECTED
    }

    private <T> List<Future<T>> submitAll(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        pool.shutdown();
        return futures;
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : submitAll(tasks)) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
