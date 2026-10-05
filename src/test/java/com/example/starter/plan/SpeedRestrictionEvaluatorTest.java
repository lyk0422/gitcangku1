package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.Section;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.service.SpeedRestrictionEvaluator;
import com.example.starter.plan.web.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 限速顺延评估器纯逻辑单元测试（不依赖 Spring 与数据库）：
 * 违例判定、最低速度叠加、按最小间隔整体顺延、已开始运行与运营日末界边界。
 */
class SpeedRestrictionEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-09-22T00:00:00Z");
    private static final Instant OP_DAY_END = Instant.parse("2026-09-23T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-20T00:00:00Z");

    private final SpeedRestrictionEvaluator evaluator = new SpeedRestrictionEvaluator();

    /** 100 km 区段，最小间隔 5 分钟。 */
    private static final Section SEC_A = new Section("SEC-A", "L1", 100, 5);

    private static Occupancy occ(String section, int startHour, int endHour) {
        return new Occupancy(0L, 1L, 0, "G1", section,
                T0.plusSeconds(startHour * 3600L), T0.plusSeconds(endHour * 3600L));
    }

    private static SpeedRestriction restriction(String key, String section, int startHour,
                                                int endHour, int maxSpeed) {
        return new SpeedRestriction(0L, key, 1, section,
                T0.plusSeconds(startHour * 3600L), T0.plusSeconds(endHour * 3600L),
                maxSpeed, RestrictionStatus.ACTIVE, "op", 0L);
    }

    @Test
    void noRestrictionOrUnregisteredSectionKeepsTimes() {
        // 无限速令：不顺延
        SpeedRestrictionEvaluator.Outcome noRestriction = evaluator.evaluate(
                List.of(occ("SEC-A", 8, 9)), Map.of("SEC-A", SEC_A), Map.of(), NOW, OP_DAY_END);
        assertThat(noRestriction.shiftMinutes()).isZero();
        assertThat(noRestriction.affected()).isEmpty();
        assertThat(noRestriction.restrictionsUsed()).isEmpty();

        // 未注册区段：不参与限速判定
        SpeedRestrictionEvaluator.Outcome unregistered = evaluator.evaluate(
                List.of(occ("SEC-X", 8, 9)), Map.of(), Map.of(), NOW, OP_DAY_END);
        assertThat(unregistered.shiftMinutes()).isZero();
    }

    @Test
    void speedBelowLimitKeepsTimes() {
        // 100 km / 2 h = 50 km/h，低于限速 80，不违例
        SpeedRestrictionEvaluator.Outcome outcome = evaluator.evaluate(
                List.of(occ("SEC-A", 8, 10)), Map.of("SEC-A", SEC_A),
                Map.of("SEC-A", List.of(restriction("R1", "SEC-A", 0, 12, 80))), NOW, OP_DAY_END);
        assertThat(outcome.shiftMinutes()).isZero();
        assertThat(outcome.occupancies().get(0).startUtc()).isEqualTo(T0.plusSeconds(8 * 3600L));
    }

    @Test
    void violationShiftsWholePlanByMinIntervalUntilClear() {
        // 100 km / 1 h = 100 km/h > 80，限速窗口 [00:00, 12:00)，最小间隔 5 分钟
        // 占用 08:00-09:00 需顺延至 12:00 之后：(12-8)*60 = 240 分钟（5 的整数倍）
        SpeedRestriction r1 = restriction("R1", "SEC-A", 0, 12, 80);
        List<Occupancy> plan = List.of(occ("SEC-A", 8, 9), occ("SEC-A", 13, 14));
        SpeedRestrictionEvaluator.Outcome outcome = evaluator.evaluate(
                plan, Map.of("SEC-A", SEC_A), Map.of("SEC-A", List.of(r1)), NOW, OP_DAY_END);

        // 整体顺延 240 分钟，两条占用一起平移，不只调整单段
        assertThat(outcome.shiftMinutes()).isEqualTo(240);
        assertThat(outcome.occupancies().get(0).startUtc()).isEqualTo(T0.plusSeconds(12 * 3600L));
        assertThat(outcome.occupancies().get(0).endUtc()).isEqualTo(T0.plusSeconds(13 * 3600L));
        assertThat(outcome.occupancies().get(1).startUtc()).isEqualTo(T0.plusSeconds(17 * 3600L));

        // 受影响明细：逐段记录受影响运行分钟数（重叠 60 分钟）、实际速度与要求速度
        assertThat(outcome.affected()).hasSize(1);
        SpeedRestrictionEvaluator.AffectedSegment seg = outcome.affected().get(0);
        assertThat(seg.sectionId()).isEqualTo("SEC-A");
        assertThat(seg.restrictionKey()).isEqualTo("R1");
        assertThat(seg.overlapMinutes()).isEqualTo(60);
        assertThat(seg.actualSpeedKmh()).isEqualTo(100.0);
        assertThat(seg.requiredMaxKmh()).isEqualTo(80);

        // 固化依据的限速令版本
        assertThat(outcome.restrictionsUsed()).containsExactly(r1);
    }

    @Test
    void overlappingRestrictionsUseLowestSpeed() {
        // 同时刻两条限速叠加：100 km/h 超过最低值 60 → 违例；以最低速度为准
        SpeedRestrictionEvaluator.Outcome outcome = evaluator.evaluate(
                List.of(occ("SEC-A", 8, 9)), Map.of("SEC-A", SEC_A),
                Map.of("SEC-A", List.of(restriction("R-HI", "SEC-A", 0, 12, 120),
                        restriction("R-LO", "SEC-A", 0, 12, 60))), NOW, OP_DAY_END);
        assertThat(outcome.shiftMinutes()).isEqualTo(240);
        assertThat(outcome.restrictionsUsed()).hasSize(1);
        assertThat(outcome.restrictionsUsed().get(0).restrictionKey()).isEqualTo("R-LO");

        // 若最低限速不低于计划速度（120 >= 100），则不违例
        SpeedRestrictionEvaluator.Outcome ok = evaluator.evaluate(
                List.of(occ("SEC-A", 8, 9)), Map.of("SEC-A", SEC_A),
                Map.of("SEC-A", List.of(restriction("R-HI", "SEC-A", 0, 12, 120))),
                NOW, OP_DAY_END);
        assertThat(ok.shiftMinutes()).isZero();
    }

    @Test
    void alreadyRunningPlanCannotBeRearranged() {
        // 当前时刻 08:30，占用 08:00-09:00 已开始运行 → 422 PLAN_ALREADY_RUNNING
        Instant runningNow = T0.plusSeconds(8 * 3600L + 30 * 60L);
        assertThatThrownBy(() -> evaluator.evaluate(
                List.of(occ("SEC-A", 8, 9)), Map.of("SEC-A", SEC_A),
                Map.of("SEC-A", List.of(restriction("R1", "SEC-A", 0, 12, 80))),
                runningNow, OP_DAY_END))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("PLAN_ALREADY_RUNNING");
                });
    }

    @Test
    void shiftBeyondOperationDayEndRejected() {
        // 运营日末界 10:30：需顺延 240 分钟但仅可容纳 90 分钟 → 422 RESTRICTION_SHIFT_CONFLICT
        Instant earlyEnd = T0.plusSeconds(10 * 3600L + 30 * 60L);
        assertThatThrownBy(() -> evaluator.evaluate(
                List.of(occ("SEC-A", 8, 9)), Map.of("SEC-A", SEC_A),
                Map.of("SEC-A", List.of(restriction("R1", "SEC-A", 0, 12, 80))),
                NOW, earlyEnd))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.code()).isEqualTo("RESTRICTION_SHIFT_CONFLICT");
                    assertThat(e.details()).isNotEmpty();
                    assertThat(e.details().get(0)).containsKey("shiftMinutesApplied");
                });
    }

    @Test
    void adjacentToRestrictionWindowIsNotViolation() {
        // 左闭右开：占用 12:00-13:00 与限速 [00:00, 12:00) 相邻不重叠 → 不违例
        SpeedRestrictionEvaluator.Outcome outcome = evaluator.evaluate(
                List.of(occ("SEC-A", 12, 13)), Map.of("SEC-A", SEC_A),
                Map.of("SEC-A", List.of(restriction("R1", "SEC-A", 0, 12, 80))), NOW, OP_DAY_END);
        assertThat(outcome.shiftMinutes()).isZero();
    }
}
