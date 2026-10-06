package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.model.Direction;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.SectionConfigService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.SectionConfigRequest;
import com.example.starter.plan.web.dto.SectionConfigResponse;
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
 * 换向间隔与配置更新的并发裁决测试：并发发布同区段反向占用仅一个成功、
 * 同版本并发配置更新仅一个成功、同 requestId 并发配置写重放首次结果。
 */
@SpringBootTest
class ClearanceConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Autowired
    private PlanService planService;

    @Autowired
    private SectionConfigService configService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentReversePublishOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        configService.upsert(section, new SectionConfigRequest(key("REQ"), "op", null, 60));
        // 已发布正向 09:00-10:00
        String forward = key("SCH");
        createDraft(forward, section, Direction.FORWARD, at(9, 0, 0), at(10, 0, 0));
        planService.publish(forward, key("REQ"));

        // 两个候选反向占用同一时隙 10:01:00-11:00（各自相对正向均满足 60 秒间隔）
        int contenders = 2;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String scheduleKey = key("SCH");
            createDraft(scheduleKey, section, Direction.REVERSE, at(10, 1, 0), at(11, 0, 0));
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
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);
        // 最终区段上为正向一条 + 反向一条
        List<PublishedSlotView> slots = planService.getPublishedSlots(DAY, section);
        assertThat(slots).hasSize(2);
    }

    @Test
    void concurrentConfigUpdateSameVersionOnlyOneSucceeds() throws Exception {
        String section = key("SEC");
        configService.upsert(section, new SectionConfigRequest(key("REQ"), "op", null, 60));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> updateConfig(section, 90),
                () -> updateConfig(section, 120)));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count()).isEqualTo(1);
        // 版本只推进一次
        SectionConfigResponse config = configService.get(section);
        assertThat(config.version()).isEqualTo(2);
    }

    @Test
    void concurrentConfigUpsertSameRequestIdReplaysFirst() throws Exception {
        String section = key("SEC");
        String requestId = key("REQ");
        int threads = 4;
        List<Callable<SectionConfigResponse>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> configService.upsert(section,
                    new SectionConfigRequest(requestId, "op", null, 60)));
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

        // 全部返回首次结果，库里只有一行配置
        assertThat(responses).allMatch(r -> r.equals(responses.get(0)));
        assertThat(responses.get(0).version()).isEqualTo(1);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_section_config WHERE section_id = ?",
                Integer.class, section);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private Outcome updateConfig(String section, int clearanceSeconds) {
        try {
            configService.upsert(section,
                    new SectionConfigRequest(key("REQ"), "op", 1, clearanceSeconds));
            return Outcome.OK;
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
            return Outcome.CONFLICT;
        }
    }

    private void createDraft(String scheduleKey, String section, Direction direction,
                             Instant start, Instant end) {
        planService.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                List.of(new OccupancyRequest("G-" + UUID.randomUUID(), section, start, end,
                        direction))));
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

    private static Instant at(int hour, int minute, int second) {
        return DAY.atTime(hour, minute, second).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
