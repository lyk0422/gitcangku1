package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.BatchPublishRequest;
import com.example.starter.plan.web.dto.BatchPublishResponse;
import com.example.starter.plan.web.dto.ChainBreakView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.OccupancyRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.RollingStockView;
import com.example.starter.plan.web.dto.StockChainResponse;
import com.example.starter.plan.web.dto.TurnaroundUpdateRequest;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 车底交路衔接与最小周转校验的真实 H2 数据库测试：
 * 衔接校验、整批发布原子性、断链记录、周转参数重校验、改签衔接、幂等边界与查询。
 */
@SpringBootTest
class StockChainApiTest {

    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    @Autowired
    private PlanService service;

    @Autowired
    private JdbcTemplate jdbc;

    // ---------- 衔接校验主流程与失败分支 ----------

    @Test
    void publishValidatesStationAndTurnaround() {
        String stock = key("STK");
        register(stock, 0, 30);
        String a = key("SCH");
        draftStock(a, stock, "BJ", "TJ", 8, 9);
        service.publish(a, key("REQ"));

        // 周转不足：09:00 终到，09:15 始发，仅 15 分钟
        String b = key("SCH");
        draftStock(b, stock, "TJ", "LF", 9.25, 10);
        ApiException insufficient = expect422(() -> service.publish(b, key("REQ")));
        assertThat(insufficient.code()).isEqualTo("CHAIN_LINK_CONFLICT");
        var detail = insufficient.details().get(0);
        assertThat(detail.get("type")).isEqualTo("TURNAROUND_INSUFFICIENT");
        assertThat(detail.get("predecessorScheduleKey")).isEqualTo(a);
        assertThat(detail.get("successorScheduleKey")).isEqualTo(b);
        assertThat(detail.get("actualGapMinutes")).isEqualTo(15L);
        assertThat(detail.get("requiredMinutes")).isEqualTo(30);
        assertThat(detail.get("breakpoint")).isEqualTo("TJ->TJ");
        // 失败保持草稿
        assertThat(service.getPlan(b).status()).isEqualTo("DRAFT");

        // 修正为间隔恰好 30 分钟后发布成功
        replace(b, 1, 9.5, 10);
        service.publish(b, key("REQ"));

        // 站点不衔接：后段始发站不是 TJ
        String c = key("SCH");
        draftStock(c, stock, "XX", "CD", 11, 12);
        ApiException mismatch = expect422(() -> service.publish(c, key("REQ")));
        assertThat(mismatch.details().get(0).get("type")).isEqualTo("STATION_MISMATCH");
        assertThat(mismatch.details().get(0).get("breakpoint")).isEqualTo("LF->XX");
        assertThat(service.getPlan(c).status()).isEqualTo("DRAFT");

        // 交路链明细
        StockChainResponse chain = service.getStockChain(stock);
        assertThat(chain.minTurnaroundMinutes()).isEqualTo(30);
        assertThat(chain.days()).hasSize(1);
        var segments = chain.days().get(0).segments();
        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).scheduleKey()).isEqualTo(a);
        assertThat(segments.get(0).gapMinutes()).isNull();
        assertThat(segments.get(1).scheduleKey()).isEqualTo(b);
        assertThat(segments.get(1).gapMinutes()).isEqualTo(30L);
        assertThat(segments.get(1).requiredMinutes()).isEqualTo(30L);
        assertThat(segments.get(1).linked()).isTrue();
        assertThat(segments.get(1).chainBreak()).isFalse();
    }

    @Test
    void publishPlanWithUnregisteredStockReturns422() {
        String plan = key("SCH");
        draftStock(plan, key("UNKNOWN-STK"), "BJ", "TJ", 8, 9);
        ApiException e = expect422(() -> service.publish(plan, key("REQ")));
        assertThat(e.code()).isEqualTo("STOCK_NOT_FOUND");
        assertThat(service.getPlan(plan).status()).isEqualTo("DRAFT");
    }

    @Test
    void stockRegistrationFieldsMustBeComplete() {
        // 只给车底不给首末站 → 400
        try {
            service.createDraft(new CreatePlanRequest(key("REQ"), key("SCH"), DAY,
                    key("STK"), null, "TJ",
                    List.of(occ(key("SEC"), 8, 9))));
            fail("应拒绝不完整的车底登记");
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        // 未登记车底的计划不受交路约束，可正常发布
        String plan = key("SCH");
        service.createDraft(new CreatePlanRequest(key("REQ"), plan, DAY,
                null, null, null, List.of(occ(key("SEC"), 8, 9))));
        service.publish(plan, key("REQ"));
    }

    // ---------- 整批发布原子性 ----------

    @Test
    void batchPublishValidatesChainAtomically() {
        String stock = key("STK");
        register(stock, 0, 10);
        String a = key("SCH");
        String b = key("SCH");
        String c = key("SCH");
        draftStock(a, stock, "BJ", "TJ", 8, 9);
        draftStock(b, stock, "TJ", "LF", 9.0833, 10);
        draftStock(c, stock, "LF", "CD", 10.5, 11);

        // B 与 A 间隔仅 5 分钟 → 整单 422，三段全部保持草稿
        String batchKey = key("REQ");
        ApiException e = expect422(() -> service.publishBatch(
                new BatchPublishRequest(batchKey, List.of(a, b, c))));
        assertThat(e.details().get(0).get("type")).isEqualTo("TURNAROUND_INSUFFICIENT");
        for (String k : List.of(a, b, c)) {
            assertThat(service.getPlan(k).status()).isEqualTo("DRAFT");
        }
        // 失败不占键：修正 B 后同一 requestKey 重试成功
        replace(b, 1, 9 + 10.0 / 60, 10);
        BatchPublishResponse response = service.publishBatch(
                new BatchPublishRequest(batchKey, List.of(a, b, c)));
        assertThat(response.plans()).hasSize(3);
        assertThat(response.plans()).allMatch(p -> p.status().equals("PUBLISHED"));

        // 同键不同参 → 409
        expect409(() -> service.publishBatch(
                new BatchPublishRequest(batchKey, List.of(a, b))));
    }

    @Test
    void batchPublishRejectsDuplicateKeys() {
        String a = key("SCH");
        draftStock(a, null, null, null, 8, 9);
        try {
            service.publishBatch(new BatchPublishRequest(key("REQ"), List.of(a, a)));
            fail("应拒绝重复 scheduleKey 的整批发布");
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void batchPublishRejectsSectionConflictBetweenBatchPlans() {
        // 两张无车底草稿占用同一区段同一时隙：整批 422，全部保持草稿
        String section = key("SEC");
        String a = key("SCH");
        String b = key("SCH");
        service.createDraft(new CreatePlanRequest(key("REQ"), a, DAY, null, null, null,
                List.of(new OccupancyRequest("G1", section, at(8), at(9)))));
        service.createDraft(new CreatePlanRequest(key("REQ"), b, DAY, null, null, null,
                List.of(new OccupancyRequest("G2", section, at(8), at(9)))));
        ApiException e = expect422(() -> service.publishBatch(
                new BatchPublishRequest(key("REQ"), List.of(a, b))));
        assertThat(e.code()).isEqualTo("SLOT_CONFLICT");
        assertThat(e.details()).isNotEmpty();
        assertThat(service.getPlan(a).status()).isEqualTo("DRAFT");
        assertThat(service.getPlan(b).status()).isEqualTo("DRAFT");
    }

    // ---------- 取消中间段断链 ----------

    @Test
    void cancelMiddleSegmentWritesBreakAndMarksSuccessorsPending() {
        String stock = key("STK");
        register(stock, 0, 10);
        String a = key("SCH");
        String b = key("SCH");
        String c = key("SCH");
        String d = key("SCH");
        draftStock(a, stock, "BJ", "TJ", 8, 9);
        draftStock(b, stock, "TJ", "LF", 9.25, 10);
        draftStock(c, stock, "LF", "CD", 10.25, 11);
        draftStock(d, stock, "CD", "CQ", 11.25, 12);
        service.publishBatch(new BatchPublishRequest(key("REQ"), List.of(a, b, c, d)));

        // 取消中间段 B
        service.cancel(b, key("REQ"));
        StockChainResponse chain = service.getStockChain(stock);
        assertThat(chain.chainBreaks()).hasSize(1);
        ChainBreakView breakView = chain.chainBreaks().get(0);
        assertThat(breakView.cancelledScheduleKey()).isEqualTo(b);
        assertThat(breakView.predecessorScheduleKey()).isEqualTo(a);
        assertThat(breakView.successorScheduleKey()).isEqualTo(c);
        assertThat(breakView.reason()).isEqualTo("CANCEL_MIDDLE");

        var segments = chain.days().get(0).segments();
        assertThat(segments).extracting(s -> s.scheduleKey()).containsExactly(a, c, d);
        assertThat(segments.get(0).rearrangePending()).isFalse();
        assertThat(segments.get(0).chainBreak()).isFalse();
        assertThat(segments.get(1).rearrangePending()).isTrue();
        assertThat(segments.get(1).chainBreak()).isTrue();
        assertThat(segments.get(2).rearrangePending()).isTrue();

        // 再取消首段 A（已无前驱）不产生新断链记录
        service.cancel(a, key("REQ"));
        assertThat(service.getStockChain(stock).chainBreaks()).hasSize(1);

        // 断链记录不可变：库内仍为 1 条且取消计划 id 为 B
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_chain_break WHERE stock_no = ?", Integer.class, stock);
        assertThat(count).isEqualTo(1);
    }

    // ---------- 周转参数修改与全量重校验 ----------

    @Test
    void turnaroundUpdateRechecksAllPublishedAdjacentPairs() {
        String stock = key("STK");
        register(stock, 0, 10);
        String a = key("SCH");
        String b = key("SCH");
        draftStock(a, stock, "BJ", "TJ", 8, 9);
        draftStock(b, stock, "TJ", "LF", 9 + 10.0 / 60, 10);
        service.publish(a, key("REQ"));
        service.publish(b, key("REQ"));

        // 调大到 20 分钟：A→B 仅 10 分钟 → 422 列出违规段，参数与版本均不变
        ApiException e = expect422(() -> service.updateTurnaround(stock,
                new TurnaroundUpdateRequest(key("REQ"), 1, 20)));
        assertThat(e.code()).isEqualTo("TURNAROUND_CONFLICT");
        assertThat(e.details()).hasSize(1);
        assertThat(e.details().get(0).get("predecessorScheduleKey")).isEqualTo(a);
        assertThat(e.details().get(0).get("successorScheduleKey")).isEqualTo(b);
        assertThat(e.details().get(0).get("actualGapMinutes")).isEqualTo(10L);
        assertThat(e.details().get(0).get("requiredMinutes")).isEqualTo(20);
        StockChainResponse unchanged = service.getStockChain(stock);
        assertThat(unchanged.minTurnaroundMinutes()).isEqualTo(10);
        assertThat(unchanged.stockVersion()).isEqualTo(1);

        // expectedVersion 不匹配 → 409
        expect409(() -> service.updateTurnaround(stock,
                new TurnaroundUpdateRequest(key("REQ"), 9, 20)));

        // 失败不占键：同一 requestKey 以合法参数（10）重放成功，版本升至 2
        String turnaroundKey = key("REQ");
        ApiException failed = expect422(() -> service.updateTurnaround(stock,
                new TurnaroundUpdateRequest(turnaroundKey, 1, 20)));
        assertThat(failed.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        RollingStockView view = service.updateTurnaround(stock,
                new TurnaroundUpdateRequest(turnaroundKey, 1, 10));
        assertThat(view.version()).isEqualTo(2);

        // 同键同参重放
        assertThat(service.updateTurnaround(stock,
                new TurnaroundUpdateRequest(turnaroundKey, 1, 10))).isEqualTo(view);
        // 同键异参 409
        expect409(() -> service.updateTurnaround(stock,
                new TurnaroundUpdateRequest(turnaroundKey, 2, 11)));

        // 未登记车底：expectedVersion=0 登记；非 0 → 404
        String missing = key("STK");
        try {
            service.updateTurnaround(missing, new TurnaroundUpdateRequest(key("REQ"), 1, 10));
            fail("未登记车底非零 expectedVersion 应 404");
        } catch (ApiException ex) {
            assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        RollingStockView created = service.updateTurnaround(missing,
                new TurnaroundUpdateRequest(key("REQ"), 0, 240));
        assertThat(created.version()).isEqualTo(1);

        // 越界分钟数 → 400
        try {
            service.updateTurnaround(missing,
                    new TurnaroundUpdateRequest(key("REQ"), 1, 0));
            fail("0 分钟应被拒绝");
        } catch (ApiException ex) {
            assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ---------- 改签衔接 ----------

    @Test
    void rescheduleRejectsTurnaroundShortageWithoutStateChange() {
        String stock = key("STK");
        register(stock, 0, 30);
        String oldA = key("SCH");
        draftStock(oldA, stock, "BJ", "TJ", 8, 9);
        service.publish(oldA, key("REQ"));
        String b = key("SCH");
        draftStock(b, stock, "TJ", "LF", 9 + 35.0 / 60, 11);
        service.publish(b, key("REQ"));

        // 新草稿 A2 终到 09:30，与 B（09:35 始发）仅隔 5 分钟 → 改签 422
        String a2 = key("SCH");
        draftStock(a2, stock, "BJ", "TJ", 8, 9.5);
        ApiException e = expect422(() -> service.reschedule(oldA,
                new RescheduleRequest(key("REQ"), a2, 1, 1)));
        assertThat(e.code()).isEqualTo("CHAIN_LINK_CONFLICT");
        assertThat(e.details().get(0).get("actualGapMinutes")).isEqualTo(5L);
        // 原计划仍发布、新计划仍草稿、无改签关联
        assertThat(service.getPlan(oldA).status()).isEqualTo("PUBLISHED");
        assertThat(service.getPlan(a2).status()).isEqualTo("DRAFT");
        assertThat(service.getRescheduleChain(oldA).chain()).hasSize(1);

        // 终到提前到 09:05 后间隔 30 分钟，改签成功
        String a3 = key("SCH");
        draftStock(a3, stock, "BJ", "TJ", 8, 9 + 5.0 / 60);
        RescheduleResponse response = service.reschedule(oldA,
                new RescheduleRequest(key("REQ"), a3, 1, 1));
        assertThat(response.oldPlan().status()).isEqualTo("CANCELLED");
        assertThat(response.newPlan().status()).isEqualTo("PUBLISHED");
        assertThat(service.getRescheduleChain(oldA).chain()).hasSize(2);
        // 交路链中新段与 B 连续
        var segments = service.getStockChain(stock).days().get(0).segments();
        assertThat(segments).extracting(s -> s.scheduleKey()).containsExactly(a3, b);
        assertThat(segments.get(1).linked()).isTrue();
    }

    // ---------- 发布幂等：失败不占键、异参 409 ----------

    @Test
    void failedPublishDoesNotOccupyRequestKey() {
        String stock = key("STK");
        register(stock, 0, 30);
        String a = key("SCH");
        draftStock(a, stock, "BJ", "TJ", 8, 9);
        service.publish(a, key("REQ"));
        String b = key("SCH");
        draftStock(b, stock, "TJ", "LF", 9.25, 10);

        String publishKey = key("REQ");
        expect422(() -> service.publish(b, publishKey));
        // 同键修正后可成功
        replace(b, 1, 9.5, 10);
        PlanResponse response = service.publish(b, publishKey);
        assertThat(response.status()).isEqualTo("PUBLISHED");
        // 同键重放返回首次结果
        assertThat(service.publish(b, publishKey)).isEqualTo(response);
        // 同键用于另一张计划 → 409
        String c = key("SCH");
        draftStock(c, null, null, null, 14, 15);
        expect409(() -> service.publish(c, publishKey));
    }

    @Test
    void stockChainQueryUnknownStockReturns404() {
        try {
            service.getStockChain(key("NOPE"));
            fail("未登记车底查询应 404");
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(e.code()).isEqualTo("STOCK_NOT_FOUND");
        }
    }

    // ---------- 辅助 ----------

    private RollingStockView register(String stock, int expectedVersion, int minutes) {
        return service.updateTurnaround(stock,
                new TurnaroundUpdateRequest(key("REQ"), expectedVersion, minutes));
    }

    private void draftStock(String scheduleKey, String stock, String origin, String destination,
                            double startHour, double endHour) {
        service.createDraft(new CreatePlanRequest(key("REQ"), scheduleKey, DAY,
                stock, origin, destination,
                List.of(occ(scheduleKey + "-SEC", startHour, endHour))));
    }

    private void replace(String scheduleKey, int expectedVersion, double startHour, double endHour) {
        service.replaceOccupancies(scheduleKey, new UpdateOccupanciesRequest(
                key("REQ"), expectedVersion,
                List.of(occ(scheduleKey + "-SEC", startHour, endHour))));
    }

    private static OccupancyRequest occ(String section, double startHour, double endHour) {
        return new OccupancyRequest("G-" + UUID.randomUUID(), section,
                at(startHour), at(endHour));
    }

    /** 运营日当天 hour（支持小数）点的 Asia/Shanghai UTC 时刻。 */
    private static Instant at(double hour) {
        long seconds = Math.round(hour * 3600);
        Instant dayStart = DAY.atStartOfDay(SH).toInstant();
        return dayStart.plusSeconds(seconds);
    }

    private ApiException expect422(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            return e;
        }
        throw new AssertionError("期望 422 但调用成功");
    }

    private void expect409(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (ApiException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
            return;
        }
        throw new AssertionError("期望 409 但调用成功");
    }

    private interface ThrowingRunnable {
        void run();
    }

    private static String key(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
