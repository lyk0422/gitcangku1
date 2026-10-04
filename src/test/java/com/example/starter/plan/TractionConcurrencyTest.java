package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.model.TractionMode;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.TractionService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.CreateSectionProfileRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
import com.example.starter.plan.web.dto.UpdateSectionProfileRequest;
import com.example.starter.plan.web.dto.UpsertTrainTractionRequest;
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
 * 牵引档案并发裁决测试（真实 H2 事务）：档案变更与发布形成合法串行结果，
 * 同键并发创建幂等，同版本并发变更仅一次成功。
 */
@SpringBootTest
class TractionConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private TractionService tractionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishAndSectionUpdateSerialize() throws Exception {
        String section = key("SEC");
        String train = key("G");
        createSection(section, true, false);
        upsertTrain(train, TractionMode.ELECTRIC);
        String scheduleKey = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest(train, section, at(8), at(9)))));

        // 并发：发布电力车计划 vs 区段断电；两者经发布锁串行，必为合法串行结果
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.publish(scheduleKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("TRACTION_CHECK_FAILED");
                        return Outcome.REJECTED;
                    }
                },
                () -> {
                    try {
                        tractionService.updateSectionProfile(section,
                                new UpdateSectionProfileRequest(key("REQ"), "test",
                                        false, false, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("PROFILE_IN_USE");
                        return Outcome.REJECTED;
                    }
                }));

        // 恰好一方成功：不能出现成功发布后档案已不适配
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        String planStatus = planService.getPlan(scheduleKey).status();
        SectionProfileResponse profile = tractionService.getSectionProfile(section);
        if (outcomes.get(0) == Outcome.OK) {
            // 发布先成交：档案变更必须 409，区段仍供电
            assertThat(planStatus).isEqualTo("PUBLISHED");
            assertThat(profile.electrified()).isTrue();
            assertThat(profile.version()).isEqualTo(1);
        } else {
            // 断电先成交：发布必须 422，计划保持草稿
            assertThat(planStatus).isEqualTo("DRAFT");
            assertThat(profile.electrified()).isFalse();
            assertThat(profile.version()).isEqualTo(2);
        }
    }

    @Test
    void concurrentSectionCreateSameRequestIdReturnsFirstResult() throws Exception {
        String section = key("SEC");
        String requestId = key("REQ");
        int threads = 4;
        List<Callable<SectionProfileResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> tractionService.createSectionProfile(
                    new CreateSectionProfileRequest(requestId, "test", section, true, true)));
        }
        List<SectionProfileResponse> responses = new ArrayList<>();
        for (Future<SectionProfileResponse> f : submitAll(tasks)) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }

        // 全部返回首次结果，库里只有一条档案
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).version()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_section_profile WHERE section_id = ?",
                Integer.class, section);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentSectionUpdatesSameVersionOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        createSection(section, true, false);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> updateTo(section, false, true),
                () -> updateTo(section, true, true),
                () -> updateTo(section, false, false)));

        // 同一期望版本并发变更：仅一次成功，其余 409 版本冲突，最终版本为 2
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.REJECTED).count()).isEqualTo(2);
        assertThat(tractionService.getSectionProfile(section).version()).isEqualTo(2);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        REJECTED
    }

    private Outcome updateTo(String section, boolean electrified, boolean dieselAllowed) {
        try {
            tractionService.updateSectionProfile(section, new UpdateSectionProfileRequest(
                    key("REQ"), "test", electrified, dieselAllowed, 1));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
            return Outcome.REJECTED;
        }
    }

    private void createSection(String section, boolean electrified, boolean dieselAllowed) {
        tractionService.createSectionProfile(new CreateSectionProfileRequest(
                key("REQ"), "test", section, electrified, dieselAllowed));
    }

    private void upsertTrain(String trainNo, TractionMode mode) {
        tractionService.upsertTrainTraction(trainNo,
                new UpsertTrainTractionRequest(key("REQ"), "test", mode));
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> tasks) throws Exception {
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : submitAll(tasks)) {
            outcomes.add(f.get(30, TimeUnit.SECONDS));
        }
        return outcomes;
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

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}