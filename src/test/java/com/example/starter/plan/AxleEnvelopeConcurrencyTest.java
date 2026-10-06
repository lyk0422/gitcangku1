package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.AxleProfileService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.SectionAxleLimitCreateRequest;
import com.example.starter.plan.web.dto.TrainAxleProfileCreateRequest;
import com.example.starter.plan.web.dto.TrainAxleProfileResponse;
import com.example.starter.plan.web.dto.TrainAxleProfileUpdateRequest;
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
 * 轴重档案与轴限核验的并发竞态测试（真实 H2 事务）：
 * 同键并发创建档案返回首次结果；档案更新与发布经全局发布锁串行化，结果必居其一且终态一致。
 */
@SpringBootTest
class AxleEnvelopeConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private AxleProfileService axleProfileService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentCreateSameTrainProfileReturnsFirstResult() throws Exception {
        String trainNo = key("TRAIN");
        String requestId = key("RID");
        int threads = 4;

        List<Callable<TrainAxleProfileResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> axleProfileService.createTrainProfile(trainNo,
                    new TrainAxleProfileCreateRequest(requestId, "dispatcher-1",
                            List.of(4000L, 8000L, 4000L))));
        }
        List<TrainAxleProfileResponse> responses = runConcurrently(tasks);

        // 全部返回首次结果，且库里只有一份档案
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_train_axle_profile WHERE train_no = ?",
                Integer.class, trainNo);
        assertThat(count).isEqualTo(1);
        Integer axleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_train_axle a JOIN rail_train_axle_profile p"
                        + " ON p.id = a.profile_id WHERE p.train_no = ?",
                Integer.class, trainNo);
        assertThat(axleCount).isEqualTo(3);
    }

    @Test
    void concurrentPublishAndProfileUpdateAreSerialized() throws Exception {
        String trainNo = key("TRAIN");
        String section = key("SEC");
        axleProfileService.createTrainProfile(trainNo,
                new TrainAxleProfileCreateRequest(key("RID"), "dispatcher-1",
                        List.of(4000L, 4000L)));
        axleProfileService.createSectionLimit(section,
                new SectionAxleLimitCreateRequest(key("RID"), "dispatcher-1", 8000L, 100000L));
        String scheduleKey = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest(trainNo, section, at(8), at(9)))));

        // 一个线程发布（当前轴重合法），另一个线程把轴重改到越限（9000 > 8000）
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        planService.publish(scheduleKey, key("REQ"));
                        return Outcome.PUBLISH_OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("AXLE_LIMIT_EXCEEDED");
                        return Outcome.PUBLISH_REJECTED;
                    }
                },
                () -> {
                    try {
                        axleProfileService.updateTrainProfile(trainNo,
                                new TrainAxleProfileUpdateRequest(key("RID"), "dispatcher-1", 1,
                                        List.of(9000L, 9000L)));
                        return Outcome.UPDATE_OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("PROFILE_UPDATE_CONFLICT");
                        return Outcome.UPDATE_REJECTED;
                    }
                }));

        // 全局发布锁串行化：要么发布先成功（更新必须 409），要么更新先成功（发布必须 422）
        PlanResponse plan = planService.getPlan(scheduleKey);
        int profileVersion = axleProfileService.getTrainProfile(trainNo).version();
        if (outcomes.get(0) == Outcome.PUBLISH_OK) {
            assertThat(outcomes.get(1)).isEqualTo(Outcome.UPDATE_REJECTED);
            assertThat(plan.status()).isEqualTo("PUBLISHED");
            assertThat(profileVersion).isEqualTo(1);
        } else {
            assertThat(outcomes.get(0)).isEqualTo(Outcome.PUBLISH_REJECTED);
            assertThat(outcomes.get(1)).isEqualTo(Outcome.UPDATE_OK);
            assertThat(plan.status()).isEqualTo("DRAFT");
            assertThat(profileVersion).isEqualTo(2);
        }
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        PUBLISH_OK,
        PUBLISH_REJECTED,
        UPDATE_OK,
        UPDATE_REJECTED
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

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
