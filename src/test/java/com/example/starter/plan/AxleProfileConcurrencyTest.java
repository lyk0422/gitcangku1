package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.AxleEnvelopeService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.SectionLimitProfileCreateRequest;
import com.example.starter.plan.web.dto.TrainAxleProfileCreateRequest;
import com.example.starter.plan.web.dto.TrainAxleProfileResponse;
import com.example.starter.plan.web.dto.TrainAxleProfileUpdateRequest;
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
 * 档案写入并发与幂等竞态测试：同 requestId 并发创建返回首次结果、
 * 并发更新按行锁串行仅一次成功、唯一键竞争不泄漏异常（H2 真实事务）。
 */
@SpringBootTest
class AxleProfileConcurrencyTest {

    @Autowired
    private AxleEnvelopeService axleService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentCreateSameRequestIdReturnsFirstResult() throws Exception {
        String trainNo = key("TRN");
        String requestId = key("RID");
        int threads = 4;
        List<Callable<TrainAxleProfileResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> axleService.createTrainProfile(new TrainAxleProfileCreateRequest(
                    requestId, "dispatcher", trainNo, List.of(4000, 8000, 4000, 6000))));
        }
        List<TrainAxleProfileResponse> responses = runConcurrently(tasks);

        // 全部返回首次完整响应，库里只有一条档案
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).version()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_train_axle_profile WHERE train_no = ?",
                Integer.class, trainNo);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentCreateSameTrainDifferentRequestIdOnlyOneCreated() throws Exception {
        String trainNo = key("TRN");
        int threads = 4;
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    axleService.createTrainProfile(new TrainAxleProfileCreateRequest(
                            key("RID"), "dispatcher", trainNo, List.of(4000, 4000)));
                    return Outcome.OK;
                } catch (ApiException e) {
                    // 唯一键竞争裁决为 409，不泄漏底层异常
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.code()).isEqualTo("TRAIN_PROFILE_EXISTS");
                    return Outcome.CONFLICT;
                }
            });
        }
        List<Outcome> outcomes = runConcurrently(tasks);
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(threads - 1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_train_axle_profile WHERE train_no = ?",
                Integer.class, trainNo);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentUpdateSameExpectedVersionOnlyOneSucceeds() throws Exception {
        String trainNo = key("TRN");
        axleService.createTrainProfile(new TrainAxleProfileCreateRequest(
                key("RID"), "dispatcher", trainNo, List.of(4000, 4000)));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> updateTo(trainNo, List.of(5000, 5000)),
                () -> updateTo(trainNo, List.of(6000, 6000))));

        // 行锁串行化：恰好一次更新成功，最终版本为 2
        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        TrainAxleProfileResponse profile = axleService.getTrainProfile(trainNo);
        assertThat(profile.version()).isEqualTo(2);
        assertThat(profile.axleWeightsKg())
                .isIn(List.of(List.of(5000, 5000), List.of(6000, 6000)));
    }

    @Test
    void concurrentSectionCreateSameRequestIdReturnsFirstResult() throws Exception {
        String sectionId = key("SEC");
        String requestId = key("RID");
        int threads = 4;
        List<Callable<com.example.starter.plan.web.dto.SectionLimitProfileResponse>> tasks =
                new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> axleService.createSectionProfile(new SectionLimitProfileCreateRequest(
                    requestId, "dispatcher", sectionId, 10000L, 18000L)));
        }
        List<com.example.starter.plan.web.dto.SectionLimitProfileResponse> responses =
                runConcurrently(tasks);
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_section_limit_profile WHERE section_id = ?",
                Integer.class, sectionId);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome updateTo(String trainNo, List<Integer> weights) {
        try {
            axleService.updateTrainProfile(trainNo, new TrainAxleProfileUpdateRequest(
                    key("RID"), "dispatcher", 1, weights));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
            return Outcome.CONFLICT;
        }
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
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
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
