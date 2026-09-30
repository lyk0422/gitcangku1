package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.service.PlatformService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlatformCapabilityRequest;
import com.example.starter.plan.web.dto.PlatformCapabilityView;
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
 * 站台到发窗口并发裁决（真实 H2）：多个计划抢同一站台同一窗口至多一方成功登记，
 * 失败一方回滚不留占位；同一发布 requestKey 并发重放只产生一次登记。
 */
@SpringBootTest
class PlatformConsistConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 24);

    @Autowired
    private PlanService planService;

    @Autowired
    private PlatformService platformService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishSamePlatformWindowOnlyOneSucceeds() throws Exception {
        String platform = uniquePlatform(300);
        int contenders = 4;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            String scheduleKey = key("SCH");
            createDraft(scheduleKey, platform, 8, 9);
            scheduleKeys.add(scheduleKey);
        }

        List<Outcome> outcomes = runConcurrently(scheduleKeys.stream()
                .<Callable<Outcome>>map(sk -> () -> {
                    try {
                        planService.publish(sk, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.details().get(0).get("type")).isEqualTo("PLATFORM_WINDOW_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                }).toList());

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);

        // 最终该站台同一窗口只有一条生效占用，全部计划合计仅一条登记（无部分占位）
        assertThat(platformService.getActiveBookings(platform)).hasSize(1);
        Integer totalBookings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_platform_booking WHERE platform_id = ?",
                Integer.class, platform);
        assertThat(totalBookings).isEqualTo(1);
        // 失败者保持草稿
        long draftCount = scheduleKeys.stream()
                .filter(sk -> "DRAFT".equals(planService.getPlan(sk).status())).count();
        assertThat(draftCount).isEqualTo(contenders - 1);
    }

    @Test
    void concurrentPublishSameRequestKeyRegistersBookingOnce() throws Exception {
        String platform = uniquePlatform(300);
        int threads = 4;
        String scheduleKey = key("SCH");
        String requestKey = key("REQ");
        createDraft(scheduleKey, platform, 14, 15);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return planService.publish(scheduleKey, requestKey).status();
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<String> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo("PUBLISHED");
        }
        pool.shutdown();

        // 同键并发只写入一条站台登记
        Integer totalBookings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_platform_booking WHERE platform_id = ?",
                Integer.class, platform);
        assertThat(totalBookings).isEqualTo(1);
        assertThat(platformService.getActiveBookings(platform)).hasSize(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private String uniquePlatform(int maxLengthM) {
        String platformId = key("PLT");
        PlatformCapabilityView view = platformService.registerCapability(
                new PlatformCapabilityRequest(key("REQ"), platformId, maxLengthM,
                        List.of("AC25KV"), true));
        assertThat(view.version()).isEqualTo(1);
        return platformId;
    }

    private void createDraft(String scheduleKey, String platformId, int startHour, int endHour) {
        OccupancyRequest occ = new OccupancyRequest("G-" + UUID.randomUUID(), key("SEC"),
                platformId, at(startHour), at(endHour));
        CreatePlanRequest request = new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                new com.example.starter.plan.web.dto.ConsistSpec(200, "AC25KV", false),
                List.of(occ));
        planService.createDraft(request);
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
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
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
