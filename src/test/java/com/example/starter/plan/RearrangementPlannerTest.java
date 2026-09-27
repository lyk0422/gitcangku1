package com.example.starter.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.service.RailLineProperties;
import com.example.starter.plan.service.RearrangementPlanner;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.SegmentImpactView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 限速重排规划器纯逻辑测试：固定时刻与固定线路参数（正常速度 300 km/h，最小间隔 10 分钟），
 * 不依赖数据库与系统时钟。
 */
class RearrangementPlannerTest {

    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-10-02T00:00:00Z");

    private final RearrangementPlanner planner =
            new RearrangementPlanner(new RailLineProperties(300, 10));

    @Test
    void noRestrictionMeansNoShift() {
        List<Occupancy> occs = List.of(occ(0, "S1", 8, 9));

        RearrangementPlanner.ShiftOutcome outcome = planner.compute(occs, List.of(), DAY_END);

        assertThat(outcome.shiftMinutes()).isZero();
        assertThat(outcome.shiftedOccupancies()).isEqualTo(occs);
        assertThat(outcome.impacts()).hasSize(1);
        assertThat(outcome.impacts().get(0).affectedMinutes()).isZero();
        assertThat(outcome.impacts().get(0).deficitMinutes()).isZero();
        assertThat(outcome.impacts().get(0).minLimitSpeedKmh()).isNull();
    }

    @Test
    void restrictionAtNormalSpeedDoesNotViolate() {
        List<Occupancy> occs = List.of(occ(0, "S1", 8, 9));
        SpeedRestriction r = restriction(1L, "S1", 8, 9, 300);

        RearrangementPlanner.ShiftOutcome outcome = planner.compute(occs, List.of(r), DAY_END);

        assertThat(outcome.shiftMinutes()).isZero();
    }

    @Test
    void adjacentRestrictionDoesNotViolate() {
        // 左闭右开：限速 [07:00, 08:00) 与占用 [08:00, 09:00) 相邻不重叠
        List<Occupancy> occs = List.of(occ(0, "S1", 8, 9));
        SpeedRestriction r = restriction(1L, "S1", 7, 8, 100);

        RearrangementPlanner.ShiftOutcome outcome = planner.compute(occs, List.of(r), DAY_END);

        assertThat(outcome.shiftMinutes()).isZero();
    }

    @Test
    void violationShiftsWholePlanByMinIntervalMultiples() {
        // 占用 08:00-09:00，限速 08:30-09:30 限 100：最小可行顺延为 90 分钟（09:30 起）
        List<Occupancy> occs = List.of(occ(0, "S1", 8, 9));
        SpeedRestriction r = restriction(7L, "S1", at(8, 30), at(9, 30), 100);

        RearrangementPlanner.ShiftOutcome outcome = planner.compute(occs, List.of(r), DAY_END);

        assertThat(outcome.shiftMinutes()).isEqualTo(90);
        assertThat(outcome.shiftMinutes() % 10).isZero();
        Occupancy shifted = outcome.shiftedOccupancies().get(0);
        assertThat(shifted.startUtc()).isEqualTo(at(9, 30));
        assertThat(shifted.endUtc()).isEqualTo(at(10, 30));
        // 原占用清单不被修改
        assertThat(occs.get(0).startUtc()).isEqualTo(at(8, 0));

        SegmentImpactView impact = outcome.impacts().get(0);
        assertThat(impact.sectionId()).isEqualTo("S1");
        assertThat(impact.affectedMinutes()).isEqualTo(30);
        // 30 分钟受限时段按 100 km/h 折算需 30 × 300 / 100 = 90 分钟
        assertThat(impact.requiredMinutes()).isEqualTo(90);
        assertThat(impact.deficitMinutes()).isEqualTo(60);
        assertThat(impact.minLimitSpeedKmh()).isEqualTo(100);
        assertThat(impact.restrictionIds()).containsExactly(7L);
    }

    @Test
    void overlappingRestrictionsUseLowestSpeed() {
        // 占用 08:00-09:00；限速令 A 全程限 150，限速令 B 前 30 分钟限 100，以最低速度为准
        List<Occupancy> occs = List.of(occ(0, "S1", 8, 9));
        SpeedRestriction a = restriction(1L, "S1", at(8, 0), at(9, 0), 150);
        SpeedRestriction b = restriction(2L, "S1", at(8, 0), at(8, 30), 100);

        RearrangementPlanner.ShiftOutcome outcome = planner.compute(occs, List.of(a, b), DAY_END);

        SegmentImpactView impact = outcome.impacts().get(0);
        assertThat(impact.affectedMinutes()).isEqualTo(60);
        // 前 30 分钟按 100 → 90 分钟；后 30 分钟按 150 → 60 分钟；合计 150
        assertThat(impact.requiredMinutes()).isEqualTo(150);
        assertThat(impact.deficitMinutes()).isEqualTo(90);
        assertThat(impact.minLimitSpeedKmh()).isEqualTo(100);
        assertThat(impact.restrictionIds()).containsExactly(1L, 2L);
        // 需顺移到两条限速令都结束之后：09:00 起，即顺延 60 分钟
        assertThat(outcome.shiftMinutes()).isEqualTo(60);
    }

    @Test
    void restrictionOnOtherSectionIsIgnored() {
        List<Occupancy> occs = List.of(occ(0, "S1", 8, 9));
        SpeedRestriction r = restriction(1L, "S2", 8, 9, 100);

        RearrangementPlanner.ShiftOutcome outcome = planner.compute(occs, List.of(r), DAY_END);

        assertThat(outcome.shiftMinutes()).isZero();
    }

    @Test
    void shiftBeyondOperationDayIsInfeasible() {
        // 限速持续到运营日结束，任何顺延都无法消除违反
        List<Occupancy> occs = List.of(occ(0, "S1", 8, 9));
        SpeedRestriction r = new SpeedRestriction(1L, "S1", at(8, 0), DAY_END, 100,
                RestrictionStatus.ACTIVE, 1, null, BASE, null);

        assertThatThrownBy(() -> planner.compute(occs, List.of(r), DAY_END))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(api.code()).isEqualTo("REARRANGE_INFEASIBLE");
                    assertThat(api.details()).hasSize(1);
                    assertThat(api.details().get(0).get("sectionId")).isEqualTo("S1");
                    // 60 分钟受限时段按 100 km/h 折算需 180 分钟，差额 120
                    assertThat(api.details().get(0).get("deficitMinutes")).isEqualTo(120L);
                });
    }

    private static Occupancy occ(int seq, String section, int startHour, int endHour) {
        return new Occupancy(0L, 1L, seq, "G1", section, at(startHour, 0), at(endHour, 0));
    }

    private static SpeedRestriction restriction(long id, String section, int startHour,
                                                int endHour, int speed) {
        return restriction(id, section, at(startHour, 0), at(endHour, 0), speed);
    }

    private static SpeedRestriction restriction(long id, String section, Instant start,
                                                Instant end, int speed) {
        return new SpeedRestriction(id, section, start, end, speed,
                RestrictionStatus.ACTIVE, 1, null, BASE, null);
    }

    private static Instant at(int hour, int minute) {
        return BASE.plusSeconds((hour * 60L + minute) * 60L);
    }
}
