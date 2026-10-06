package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.ProfileService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.SectionProfileRequest;
import com.example.starter.plan.web.dto.SectionProfileUpdateRequest;
import com.example.starter.plan.web.dto.TrainProfileRequest;
import com.example.starter.plan.web.dto.TrainProfileResponse;
import java.math.BigDecimal;
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
 * 制动包络并发测试：档案修改与发布经全局发布锁串行化，共同检查完整后态；
 * 同 requestId 并发创建档案只生效一次。
 */
@SpringBootTest
class PlanEnvelopeConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private ProfileService profileService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentProfileUpdateAndPublishSerializedToConsistentPostState() throws Exception {
        String trainNo = key("G");
        String sectionId = key("SEC");
        profileService.createTrainProfile(new TrainProfileRequest(key("REQ"), "op-1", trainNo,
                new BigDecimal("1.000"), new BigDecimal("2.000")));
        profileService.createSectionProfile(new SectionProfileRequest(key("REQ"), "op-1",
                sectionId, new BigDecimal("35.000")));
        String scheduleKey = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest(trainNo, sectionId, at(8), at(9),
                        new BigDecimal("10")))));

        // 并发：收紧防护距离到 34.999（使占用越限） vs 发布该计划
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        profileService.updateSectionProfile(sectionId,
                                new SectionProfileUpdateRequest(key("REQ"), "op-1",
                                        new BigDecimal("34.999"), 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(e.code()).isEqualTo("ENVELOPE_CONFIG_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        planService.publish(scheduleKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("ENVELOPE_EXCEEDED");
                        return Outcome.REJECTED;
                    }
                }));

        Outcome updateOutcome = outcomes.get(0);
        Outcome publishOutcome = outcomes.get(1);
        String status = planService.getPlan(scheduleKey).status();
        BigDecimal protectD = profileService.getSectionProfile(sectionId).protectDistanceD();
        if (updateOutcome == Outcome.OK) {
            // 修改先提交：发布必须因越限 422，计划保持草稿，配置已生效
            assertThat(publishOutcome).isEqualTo(Outcome.REJECTED);
            assertThat(status).isEqualTo("DRAFT");
            assertThat(protectD).isEqualByComparingTo("34.999");
        } else {
            // 发布先提交：修改必须 409 不生效，计划已发布，配置保持 35
            assertThat(updateOutcome).isEqualTo(Outcome.CONFLICT);
            assertThat(publishOutcome).isEqualTo(Outcome.OK);
            assertThat(status).isEqualTo("PUBLISHED");
            assertThat(protectD).isEqualByComparingTo("35");
        }
    }

    @Test
    void concurrentCreateSameProfileRequestIdReturnsFirstResult() throws Exception {
        String trainNo = key("G");
        String requestId = key("REQ");
        int threads = 4;
        TrainProfileRequest request = new TrainProfileRequest(requestId, "op-1", trainNo,
                new BigDecimal("1.000"), new BigDecimal("2.000"));

        List<Callable<TrainProfileResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> profileService.createTrainProfile(request));
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

        // 全部返回首次结果，且库里只有一条档案
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_train_profile WHERE train_no = ?",
                Integer.class, trainNo);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT,
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
