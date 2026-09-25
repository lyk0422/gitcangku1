package com.example.starter.workblock;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.support.TestSystemClock;
import com.example.starter.workblock.service.WorkBlockService;
import com.example.starter.workblock.web.dto.CancelWorkBlockRequest;
import com.example.starter.workblock.web.dto.CreateWorkBlockRequest;
import com.example.starter.workblock.web.dto.WorkBlockView;
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
 * 施工窗口并发裁决、同键并发幂等、施工单创建与计划发布竞争、
 * 以及“已开始不可取消”（可控时钟）的真实 H2 数据库测试。
 */
@SpringBootTest
class WorkBlockConcurrencyTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2030, 6, 1);

    @Autowired
    private WorkBlockService workBlockService;

    @Autowired
    private PlanService planService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestSystemClock clock;

    @Test
    void concurrentOverlappingCreateOnlyOneSucceeds() throws Exception {
        String section = registerSection();
        int contenders = 4;
        List<Outcome> outcomes = runConcurrently(createOverlapTasks(contenders, section));

        assertThat(outcomes.stream().filter(o -> o == Outcome.OK).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(o -> o == Outcome.CONFLICT).count())
                .isEqualTo(contenders - 1);

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_work_block w"
                        + " JOIN rail_work_block_section s ON s.work_block_id = w.id"
                        + " WHERE s.section_id = ? AND w.status = 'ACTIVE'",
                Integer.class, section);
        assertThat(activeCount).isEqualTo(1);
    }

    @Test
    void concurrentCreateSameRequestKeyReturnsFirstResult() throws Exception {
        String section = registerSection();
        String workKey = key("WB");
        String requestKey = key("REQ");
        int threads = 4;
        CreateWorkBlockRequest request = new CreateWorkBlockRequest(requestKey, workKey,
                at(8), at(10), List.of(section), "op-concurrent");

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<WorkBlockView>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return workBlockService.create(request);
            }));
        }
        ready.await();
        go.countDown();
        List<WorkBlockView> views = new ArrayList<>();
        for (Future<WorkBlockView> f : futures) {
            views.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(views).allMatch(v -> v.equals(views.get(0)));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_work_block WHERE work_key = ?",
                Integer.class, workKey);
        assertThat(count).isEqualTo(1);
        Integer idemCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE op_type = 'WORK_CREATE'"
                        + " AND request_key = ?",
                Integer.class, requestKey);
        assertThat(idemCount).isEqualTo(1);
    }

    @Test
    void concurrentWorkBlockCreateAndPlanPublishAreAdjudicatedInCommitOrder() throws Exception {
        String section = registerSection();
        // 已存在一张草稿计划占用 08:00-09:00，与待创建窗口完全重叠
        String planKey = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), planKey, DAY,
                List.of(new OccupancyRequest("G1", section, at(8), at(9)))));

        List<Outcome> outcomes = runConcurrently(List.of(
                () -> {
                    // 按题面创建施工单只做施工单间重叠校验，故本事务恒成功
                    workBlockService.create(new CreateWorkBlockRequest(
                            key("REQ"), key("WB"), at(8), at(9), List.of(section), "op-a"));
                    return Outcome.OK;
                },
                () -> {
                    try {
                        planService.publish(planKey, key("REQ"));
                        return Outcome.OK;
                    } catch (ApiException e) {
                        // 窗口先提交时发布必须 422 并整单回滚
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(e.code()).isEqualTo("WORK_BLOCK_CONFLICT");
                        return Outcome.CONFLICT;
                    }
                }));

        // 施工单创建恒成功；计划发布成败取决于两者在全局锁上的提交顺序
        assertThat(outcomes.get(0)).isEqualTo(Outcome.OK);
        String planStatus = planService.getPlan(planKey).status();
        Integer windowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_work_block w"
                        + " JOIN rail_work_block_section s ON s.work_block_id = w.id"
                        + " WHERE s.section_id = ? AND w.status = 'ACTIVE'",
                Integer.class, section);
        assertThat(windowCount).isEqualTo(1);
        if (planStatus.equals("DRAFT")) {
            // 窗口先提交：发布被 422 回滚，计划仍草稿、占用不变
            assertThat(outcomes.get(1)).isEqualTo(Outcome.CONFLICT);
            assertThat(planService.getPlan(planKey).occupancies()).hasSize(1);
        } else {
            // 计划先提交：按题面创建不校验已发布计划，两者共存
            assertThat(planStatus).isEqualTo("PUBLISHED");
            assertThat(outcomes.get(1)).isEqualTo(Outcome.OK);
        }

        // 无论裁决顺序如何，此后再发布同区段相交的新计划必须 422
        String laterPlan = key("SCH");
        planService.createDraft(new CreatePlanRequest(key("REQ"), laterPlan, DAY,
                List.of(new OccupancyRequest("G2", section, at(8), at(9)))));
        try {
            planService.publish(laterPlan, key("REQ"));
            org.assertj.core.api.Assertions.fail("相交计划应被 422 拒绝");
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            // 计划先提交：先撞已发布计划 SLOT_CONFLICT；窗口先提交：撞窗口 WORK_BLOCK_CONFLICT
            assertThat(e.code()).isIn("SLOT_CONFLICT", "WORK_BLOCK_CONFLICT");
            if (planStatus.equals("DRAFT")) {
                assertThat(e.code()).isEqualTo("WORK_BLOCK_CONFLICT");
            }
        }
        assertThat(planService.getPlan(laterPlan).status()).isEqualTo("DRAFT");
    }

    @Test
    void startedWorkBlockCannotBeCancelledButFutureOneCan() {
        String section = registerSection();
        String workKey = key("WB");
        try {
            // 固定时钟到窗口开始之后：窗口 08:00-10:00，当前 09:00 → 已开始
            clock.setFixed(at(9).toEpochMilli());
            workBlockService.create(new CreateWorkBlockRequest(
                    key("REQ"), workKey, at(8), at(10), List.of(section), "op-time"));

            try {
                workBlockService.cancel(workKey, new CancelWorkBlockRequest(key("REQ"), "op-time"));
                org.assertj.core.api.Assertions.fail("已开始施工单应拒绝取消");
            } catch (ApiException e) {
                assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.code()).isEqualTo("WORK_BLOCK_ALREADY_STARTED");
            }
            assertThat(workBlockService.get(workKey).status()).isEqualTo("ACTIVE");
            assertThat(workBlockService.getCancellations(workKey)).isEmpty();

            // 恰在开始时刻（左闭）也视为已开始
            clock.setFixed(at(8).toEpochMilli());
            try {
                workBlockService.cancel(workKey, new CancelWorkBlockRequest(key("REQ"), "op-time"));
                org.assertj.core.api.Assertions.fail("到达开始时刻应拒绝取消");
            } catch (ApiException e) {
                assertThat(e.code()).isEqualTo("WORK_BLOCK_ALREADY_STARTED");
            }
        } finally {
            clock.reset();
        }

        // 时钟恢复为真实当前（2030 年窗口尚在未来）→ 可取消并释放
        WorkBlockView cancelled = workBlockService.cancel(workKey,
                new CancelWorkBlockRequest(key("REQ"), "op-time"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(workBlockService.getCancellations(workKey)).hasSize(1);
        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_work_block WHERE work_key = ? AND status = 'ACTIVE'",
                Integer.class, workKey);
        assertThat(activeCount).isEqualTo(0);
    }

    // ---------- 辅助 ----------

    private enum Outcome {
        OK,
        CONFLICT
    }

    private List<Callable<Outcome>> createOverlapTasks(int contenders, String section) {
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            tasks.add(() -> {
                try {
                    workBlockService.create(new CreateWorkBlockRequest(
                            key("REQ"), key("WB"), at(8), at(9), List.of(section), "op-race"));
                    return Outcome.OK;
                } catch (ApiException e) {
                    assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
                    return Outcome.CONFLICT;
                }
            });
        }
        return tasks;
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

    private String registerSection() {
        String sectionId = "SEC-" + UUID.randomUUID();
        jdbc.update("MERGE INTO rail_section KEY(section_id) VALUES (?, ?, ?)",
                sectionId, "并发测试区段-" + sectionId, 0L);
        return sectionId;
    }

    private static Instant at(int hour) {
        return DAY.atTime(hour, 0).atZone(SH).toInstant();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
