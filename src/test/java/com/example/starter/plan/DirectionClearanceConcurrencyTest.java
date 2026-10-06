package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.SectionConfigService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.SectionConfigCreateRequest;
import com.example.starter.plan.web.dto.SectionConfigResponse;
import com.example.starter.plan.web.dto.SectionConfigUpdateRequest;
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
 * 单线换向并发与幂等竞态测试：并发反向发布仅一个成功、
 * 同 requestId 并发配置创建重放首次结果、并发配置更新按版本裁决。
 */
@SpringBootTest
class DirectionClearanceConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private SectionConfigService configService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentReversePublishExactlyOneSucceeds() throws Exception {
        String section = key("SEC");
        configService.create(new SectionConfigCreateRequest(
                key("REQ"), "op-1", section, "UP", "DOWN", 60));
        // 已发布正向 09:00-10:00
        String up = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), up, DAY,
                List.of(occ(section, 9, 0, 0, 10, 0, 0, "UP"))));
        planService.publish(up, key("REQ"));

        // 四个反向候选 10:01:00 开始（各自满足 60 秒间隔，但互相重叠）
        int contenders = 4;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String scheduleKey = key("SCH");
            planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                    List.of(occ(section, 10, 1, 0, 11, 0, 0, "DOWN"))));
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

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        // 最终区段上为正向一条 + 反向一条
        List<PublishedSlotView> slots = planService.getPublishedSlots(DAY, section);
        assertThat(slots).hasSize(2);
        assertThat(slots.stream().map(PublishedSlotView::direction).distinct().count())
                .isEqualTo(2);
    }

    @Test
    void concurrentConfigCreateSameRequestIdReturnsFirstResult() throws Exception {
        String section = key("SEC");
        String requestId = key("REQ");
        int threads = 4;
        SectionConfigCreateRequest request =
                new SectionConfigCreateRequest(requestId, "op-1", section, "UP", "DOWN", 60);

        List<Callable<SectionConfigResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> configService.create(request));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<SectionConfigResponse>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<SectionConfigResponse> responses = new ArrayList<>();
        for (Future<SectionConfigResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 全部返回首次结果，库里只有一条配置
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).version()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_section_config WHERE section_id = ?",
                Integer.class, section);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void concurrentConfigUpdateSameVersionExactlyOneSucceeds() throws Exception {
        String section = key("SEC");
        configService.create(new SectionConfigCreateRequest(
                key("REQ"), "op-1", section, "UP", "DOWN", 60));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        configService.update(section, new SectionConfigUpdateRequest(
                                key("REQ"), "op-1", 1, "UP", "DOWN", 30));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        configService.update(section, new SectionConfigUpdateRequest(
                                key("REQ"), "op-1", 1, "UP", "DOWN", 45));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        // 最终版本恰好推进一次
        SectionConfigResponse current = configService.get(section);
        assertThat(current.version()).isEqualTo(2);
        assertThat(current.clearanceSeconds()).isIn(30, 45);
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

    private static OccupancyRequest occ(String section, int startHour, int startMinute,
                                        int startSecond, int endHour, int endMinute, int endSecond,
                                        String direction) {
        return new OccupancyRequest("G-" + UUID.randomUUID(), section,
                at(startHour, startMinute, startSecond), at(endHour, endMinute, endSecond),
                direction);
    }

    private static Instant at(int hour, int minute, int second) {
        return DAY.atTime(hour, minute, second).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
