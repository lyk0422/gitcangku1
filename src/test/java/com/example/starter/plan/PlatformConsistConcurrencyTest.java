package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlatformRegistrationRequest;
import com.example.starter.plan.web.dto.PlatformView;
import com.example.starter.plan.web.dto.RescheduleRequest;
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
 * 编组到发站台占用的并发裁决测试（真实 H2 内存库）：
 * 并发发布抢同一站台同一 UTC 窗口至多一方成功；同一登记 requestKey 并发只产生一个能力版本；
 * 改签与第三方发布竞争同一站台窗口按事务提交顺序裁决，至多一方成功。
 */
@SpringBootTest
class PlatformConsistConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentPublishSamePlatformWindowOnlyOneSucceeds() throws Exception {
        String platformId = registerPlatform(220, List.of("AC25KV"), true);
        int contenders = 4;
        List<String> scheduleKeys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            // 每计划使用不同区段，排除区段冲突干扰，使站台同窗口裁决成为唯一闸门
            String scheduleKey = key("SCH");
            createPlatformDraft(scheduleKey, key("SEC"), platformId, 8, 9);
            scheduleKeys.add(scheduleKey);
        }

        List<Outcome> outcomes = runConcurrently(scheduleKeys.stream()
                .<Callable<Outcome>>map(sk -> () -> {
                    try {
                        service.publish(sk, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("SLOT_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                }).toList());

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);

        // 最终该站台窗口只有一条生效占用，其余计划仍为草稿
        assertThat(service.getPlatformOccupancies(platformId)).hasSize(1);
        long publishedDrafts = scheduleKeys.stream()
                .filter(sk -> service.getPlan(sk).status().equals("PUBLISHED"))
                .count();
        assertThat(publishedDrafts).isEqualTo(1);
    }

    @Test
    void concurrentRegisterSameRequestKeyCreatesSingleVersion() throws Exception {
        String platformId = key("PLT");
        String requestKey = key("REQ");
        int threads = 4;
        List<Callable<PlatformView>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> service.registerPlatform(platformId, new PlatformRegistrationRequest(
                    requestKey, "并发登记站台", 220, List.of("AC25KV"), true)));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<PlatformView>> futures = tasks.stream()
                .map(t -> pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                })).toList();
        ready.await();
        go.countDown();
        List<PlatformView> views = new ArrayList<>();
        for (Future<PlatformView> f : futures) {
            views.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(views).allMatch(v -> v.version() == 1 && v.platformId().equals(platformId));
        Integer historyRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_platform_capability_history WHERE platform_id = ?",
                Integer.class, platformId);
        assertThat(historyRows).isEqualTo(1);
        assertThat(service.getPlatform(platformId).version()).isEqualTo(1);
    }

    @Test
    void concurrentRescheduleAndPublishRaceForSamePlatformWindowOnlyOneWins() throws Exception {
        String platformId = registerPlatform(220, List.of("AC25KV"), true);
        String oldKey = key("SCH");
        createPlatformDraft(oldKey, key("SEC"), platformId, 8, 9);
        service.publish(oldKey, key("REQ"));

        // 改签新草稿与第三方草稿竞争同一站台窗口 10:00-11:00，使用不同区段排除区段冲突
        String newKey = key("SCH");
        String thirdParty = key("SCH");
        createPlatformDraft(newKey, key("SEC"), platformId, 10, 11);
        createPlatformDraft(thirdParty, key("SEC"), platformId, 10, 11);

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    try {
                        service.reschedule(oldKey, new RescheduleRequest(
                                key("REQ"), newKey, 1, 1));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                },
                () -> {
                    try {
                        service.publish(thirdParty, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        return Outcome.CONFLICT;
                    }
                }));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        String newStatus = service.getPlan(newKey).status();
        String thirdStatus = service.getPlan(thirdParty).status();
        if (outcomes.get(0) == Outcome.OK) {
            assertThat(service.getPlan(oldKey).status()).isEqualTo("CANCELLED");
            assertThat(newStatus).isEqualTo("PUBLISHED");
            assertThat(thirdStatus).isEqualTo("DRAFT");
        } else {
            assertThat(service.getPlan(oldKey).status()).isEqualTo("PUBLISHED");
            assertThat(newStatus).isEqualTo("DRAFT");
            assertThat(thirdStatus).isEqualTo("PUBLISHED");
        }
        // 该站台 10:00-11:00 最终只有一条生效占用
        long windowTen = service.getPlatformOccupancies(platformId).stream()
                .filter(o -> o.startUtc().equals(at(10)))
                .count();
        assertThat(windowTen).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private String registerPlatform(int maxLength, List<String> powerSystems,
                                    boolean accessible) {
        String platformId = key("PLT");
        PlatformView view = service.registerPlatform(platformId, new PlatformRegistrationRequest(
                key("REQ"), platformId + "名称", maxLength, powerSystems, accessible));
        assertThat(view.version()).isEqualTo(1);
        return platformId;
    }

    private void createPlatformDraft(String scheduleKey, String section, String platformId,
                                     int startHour, int endHour) {
        OccupancyRequest occ = new OccupancyRequest("G-" + UUID.randomUUID(), section,
                at(startHour), at(endHour), platformId, 200, "AC25KV", false);
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY, List.of(occ)));
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
