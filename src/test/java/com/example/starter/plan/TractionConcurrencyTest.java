package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.model.TractionMode;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.TractionService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileResponse;
import com.example.starter.plan.web.dto.TrainProfileRequest;
import com.example.starter.plan.web.dto.TrainProfileResponse;
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
 * 牵引档案并发测试：档案变更与发布经全局发布锁串行化，不出现成功发布后档案已不适配；
 * 同 requestId 并发写入全部得到首次结果；同版本并发变更仅一次成功。
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
    void concurrentPublishAndSectionProfileChangeSerialize() throws Exception {
        String section = key("SEC");
        String trainNo = key("TRN");
        tractionService.upsertTrainProfile(trainNo,
                new TrainProfileRequest(key("RID"), "tester", TractionMode.ELECTRIC, 0));
        tractionService.upsertSectionProfile(section,
                new SectionProfileRequest(key("RID"), "tester", true, false, 0));
        String scheduleKey = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest(trainNo, section, at(8), at(9)))));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.publish(scheduleKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 档案先变更时发布应得 422 牵引不适配
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("TRACTION_CHECK_FAILED");
                        return Outcome.REJECTED;
                    }
                },
                () -> {
                    try {
                        tractionService.upsertSectionProfile(section,
                                new SectionProfileRequest(key("RID"), "tester", false, false, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 发布先提交时档案变更应得 409 守卫冲突
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("PROFILE_CHANGE_CONFLICT");
                        return Outcome.REJECTED;
                    }
                }));

        // 串行结果：恰好一方成功，且最终状态自洽
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        SectionProfileResponse profile = tractionService.findSectionProfile(section).orElseThrow();
        if (outcomes.get(0) == Outcome.OK) {
            // 发布胜出：计划已发布，档案保持供电、版本 1，快照冻结供电
            assertThat(planService.getPlan(scheduleKey).status()).isEqualTo("PUBLISHED");
            assertThat(profile.electrified()).isTrue();
            assertThat(profile.version()).isEqualTo(1);
            assertThat(tractionService.getSnapshot(scheduleKey).snapshots()).hasSize(1);
            assertThat(tractionService.getSnapshot(scheduleKey).snapshots().get(0).electrified())
                    .isTrue();
        } else {
            // 档案变更胜出：计划仍草稿，档案已断电、版本 2，无快照
            assertThat(planService.getPlan(scheduleKey).status()).isEqualTo("DRAFT");
            assertThat(profile.electrified()).isFalse();
            assertThat(profile.version()).isEqualTo(2);
            assertThat(tractionService.getSnapshot(scheduleKey).snapshots()).isEmpty();
        }
    }

    @Test
    void concurrentSameRequestIdProfileCreateReturnsFirstResult() throws Exception {
        String trainNo = key("TRN");
        String requestId = key("RID");
        int threads = 4;

        List<Callable<TrainProfileResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> tractionService.upsertTrainProfile(trainNo,
                    new TrainProfileRequest(requestId, "tester", TractionMode.DIESEL, 0)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<TrainProfileResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<TrainProfileResponse> responses = new ArrayList<>();
        for (Future<TrainProfileResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次结果，库里只有一条档案且版本为 1
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).version()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_train_profile WHERE train_no = ?",
                Integer.class, trainNo);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentSectionUpdatesSameExpectedVersionOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        tractionService.upsertSectionProfile(section,
                new SectionProfileRequest(key("RID"), "tester", true, true, 0));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        tractionService.upsertSectionProfile(section,
                                new SectionProfileRequest(key("RID"), "tester", false, true, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.REJECTED;
                    }
                },
                () -> {
                    try {
                        tractionService.upsertSectionProfile(section,
                                new SectionProfileRequest(key("RID"), "tester", true, false, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.REJECTED;
                    }
                }));

        // 同一期望版本仅一次变更成功，最终版本为 2
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(tractionService.findSectionProfile(section).orElseThrow().version())
                .isEqualTo(2);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        REJECTED
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