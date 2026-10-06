package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.SubstationService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BindSectionRequest;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.CreateSubstationRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.SubstationResponse;
import com.example.starter.plan.web.dto.UpdateSubstationRequest;
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
 * 供电预算并发与竞态测试（真实 H2 事务）：并发发布共享预算互斥、
 * 调上限与发布按提交顺序裁决、同 requestId 并发创建不暴露唯一键错误。
 */
@SpringBootTest
class SubstationPowerConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private SubstationService substationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishSharesPowerBudget() throws Exception {
        String substation = key("SUB");
        substationService.createSubstation(
                new CreateSubstationRequest(key("RID"), "OP", substation, 100));
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        substationService.bindSection(sectionA, new BindSectionRequest(key("RID"), "OP", substation, 1));
        substationService.bindSection(sectionB, new BindSectionRequest(key("RID"), "OP", substation, 1));

        // 4 张草稿各取 60 千瓦同时段，分布在两个已绑定区段
        int contenders = 4;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String scheduleKey = key("SCH");
            String section = i % 2 == 0 ? sectionA : sectionB;
            planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                    List.of(new OccupancyRequest("G" + i, section, at(10, 0), at(11, 0), 60))));
            scheduleKeys.add(scheduleKey);
        }

        List<Outcome> outcomes = runConcurrently(scheduleKeys.stream()
                .<Callable<Outcome>>map(sk -> () -> {
                    try {
                        planService.publish(sk, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }).toList());

        // 预算 100 仅容纳一单 60 千瓦
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);
        Integer published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_day_plan p WHERE p.status = 'PUBLISHED'"
                        + " AND p.schedule_key IN ('" + String.join("','", scheduleKeys) + "')",
                Integer.class);
        assertThat(published).isEqualTo(1);
    }

    @Test
    void concurrentLimitDecreaseAndPublishApplyInCommitOrder() throws Exception {
        String substation = key("SUB");
        substationService.createSubstation(
                new CreateSubstationRequest(key("RID"), "OP", substation, 100));
        String sectionA = key("SEC");
        String sectionB = key("SEC");
        substationService.bindSection(sectionA, new BindSectionRequest(key("RID"), "OP", substation, 1));
        substationService.bindSection(sectionB, new BindSectionRequest(key("RID"), "OP", substation, 1));

        // 已发布 60 千瓦；草稿再加 40 千瓦（100 内容纳，80 内超限）
        String published = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), published, DAY,
                List.of(new OccupancyRequest("G1", sectionA, at(10, 0), at(11, 0), 60))));
        planService.publish(published, key("REQ"));
        String draft = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), draft, DAY,
                List.of(new OccupancyRequest("G2", sectionB, at(10, 0), at(11, 0), 40))));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.publish(draft, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        substationService.updateLimit(substation,
                                new UpdateSubstationRequest(key("RID"), "OP", 80, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        // 发布与调上限串行裁决：恰好一个成功
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        SubstationResponse current = substationService.getSubstation(substation);
        if (outcomes.get(0) == Outcome.OK) {
            // 发布先生效：100 千瓦占满，调低到 80 必 409，上限保持 100
            assertThat(current.powerLimitKw()).isEqualTo(100);
            assertThat(current.version()).isEqualTo(1);
            assertThat(planService.getPlan(draft).status()).isEqualTo("PUBLISHED");
        } else {
            // 调低先生效：预算 80，40 千瓦草稿超限 422 保持草稿
            assertThat(current.powerLimitKw()).isEqualTo(80);
            assertThat(current.version()).isEqualTo(2);
            assertThat(planService.getPlan(draft).status()).isEqualTo("DRAFT");
        }
    }

    @Test
    void concurrentCreateSameRequestIdReturnsFirstResult() throws Exception {
        String substation = key("SUB");
        String requestId = key("RID");
        int threads = 4;

        List<Callable<SubstationResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> substationService.createSubstation(
                    new CreateSubstationRequest(requestId, "OP", substation, 100)));
        }
        List<SubstationResponse> responses = new ArrayList<>();
        for (Future<SubstationResponse> f : submitAll(tasks)) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }

        // 全部返回首次结果，库里只有一行，唯一键错误不外溢
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_substation WHERE substation_key = ?",
                Integer.class, substation);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : submitAll(tasks)) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        return outcomes;
    }

    private <T> List<Future<T>> submitAll(List<Callable<T>> tasks) throws InterruptedException {
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

    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
