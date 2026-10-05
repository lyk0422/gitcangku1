package com.example.starter.plan.service;

import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.Section;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 气象限速顺延评估（纯逻辑，无数据库访问）。
 *
 * <p>违例判定：计划占用落在已注册区段上，且与生效限速令时段（左闭右开）重叠，
 * 按区段长度与占用时长推算的运行速度超过限速值。同一区段同一时刻多条限速叠加时，
 * 任一限速被突破即违例（等效于以最低速度为准）。
 *
 * <p>顺延规则：发现违例时按违例区段所属线路的最小间隔整体顺延整张计划
 * （所有占用平移同一时长，不只调整单段），重复评估直至不再违例；
 * 顺延不得越过运营日末界，已开始运行的计划不可重排。
 */
@Component
public class SpeedRestrictionEvaluator {

    /** 顺延迭代上限（防御性约束，正常场景由运营日末界先行终止）。 */
    static final int MAX_ITERATIONS = 2000;

    /**
     * 受影响区段明细：逐段记录受影响时段的运行分钟数与速度差额。
     *
     * @param trainNo           列车编号
     * @param sectionId         区段 ID
     * @param restrictionKey    被突破的限速令业务键
     * @param restrictionVersion 被突破的限速令版本
     * @param overlapMinutes    占用与限速时段的重叠分钟数（受影响运行分钟数）
     * @param actualSpeedKmh    计划推算速度，单位 km/h
     * @param requiredMaxKmh    限速要求值，单位 km/h
     */
    public record AffectedSegment(String trainNo, String sectionId, String restrictionKey,
                                  int restrictionVersion, long overlapMinutes,
                                  double actualSpeedKmh, int requiredMaxKmh) {
    }

    /**
     * 评估结果。
     *
     * @param occupancies      顺延后的占用清单（无违例时与原清单相等）
     * @param shiftMinutes     整体顺延分钟数，0 表示未顺延
     * @param affected         按原计划时刻逐段计算的受影响明细
     * @param restrictionsUsed 重排实际依据的限速令版本（去重，按业务键升序）
     */
    public record Outcome(List<Occupancy> occupancies, long shiftMinutes,
                          List<AffectedSegment> affected,
                          List<SpeedRestriction> restrictionsUsed) {
    }

    /**
     * 评估计划占用是否违反限速，必要时整体顺延。
     *
     * @param occupancies  原计划占用（按计划内序号升序）
     * @param sections     区段注册信息，按区段 ID 索引
     * @param restrictions 生效限速令，按区段 ID 分组
     * @param now          当前时刻（可控时钟），已开始运行的计划不可重排
     * @param opDayEnd     运营日末界（不含），顺延不得越过
     */
    public Outcome evaluate(List<Occupancy> occupancies, Map<String, Section> sections,
                            Map<String, List<SpeedRestriction>> restrictions,
                            Instant now, Instant opDayEnd) {
        List<Occupancy> current = occupancies;
        long shiftMinutes = 0;
        List<AffectedSegment> firstPassAffected = null;
        Map<String, SpeedRestriction> used = new LinkedHashMap<>();
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            List<Violation> violations = findViolations(current, sections, restrictions);
            if (violations.isEmpty()) {
                List<SpeedRestriction> usedList = used.values().stream()
                        .sorted(java.util.Comparator.comparing(SpeedRestriction::restrictionKey)
                                .thenComparing(SpeedRestriction::version))
                        .toList();
                return new Outcome(current, shiftMinutes,
                        firstPassAffected == null ? List.of() : firstPassAffected, usedList);
            }
            if (firstPassAffected == null) {
                firstPassAffected = violations.stream().map(Violation::toAffected).toList();
            }
            violations.forEach(v -> used.putIfAbsent(
                    v.restriction.restrictionKey() + "#" + v.restriction.version(), v.restriction));
            assertNotRunning(occupancies, now);
            Violation first = violations.get(0);
            Section section = sections.get(first.occupancy.sectionId());
            shiftMinutes += section.minIntervalMinutes();
            current = shiftAll(current, section.minIntervalMinutes());
            assertWithinOperationDay(current, opDayEnd, shiftMinutes, section);
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESTRICTION_SHIFT_CONFLICT",
                "限速顺延超过迭代上限，无法在运营日内满足限速要求");
    }

    private List<Violation> findViolations(List<Occupancy> occupancies,
                                           Map<String, Section> sections,
                                           Map<String, List<SpeedRestriction>> restrictions) {
        List<Violation> violations = new ArrayList<>();
        for (Occupancy o : occupancies) {
            Section section = sections.get(o.sectionId());
            if (section == null) {
                continue;
            }
            double durationHours = (o.endUtc().toEpochMilli() - o.startUtc().toEpochMilli())
                    / 3_600_000.0;
            double speedKmh = section.lengthKm() / durationHours;
            for (SpeedRestriction r : restrictions.getOrDefault(o.sectionId(), List.of())) {
                boolean overlap = o.startUtc().isBefore(r.endUtc())
                        && r.startUtc().isBefore(o.endUtc());
                if (overlap && speedKmh > r.maxSpeedKmh()) {
                    long overlapMinutes = (Math.min(o.endUtc().toEpochMilli(), r.endUtc().toEpochMilli())
                            - Math.max(o.startUtc().toEpochMilli(), r.startUtc().toEpochMilli()))
                            / 60_000L;
                    violations.add(new Violation(o, r, overlapMinutes, speedKmh));
                }
            }
        }
        return violations;
    }

    private void assertNotRunning(List<Occupancy> original, Instant now) {
        Instant earliest = original.stream().map(Occupancy::startUtc).min(Instant::compareTo)
                .orElse(now);
        if (!earliest.isAfter(now)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "PLAN_ALREADY_RUNNING");
            detail.put("firstStartUtc", earliest.toString());
            detail.put("nowUtc", now.toString());
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_ALREADY_RUNNING",
                    "计划已开始运行，不可按限速重排", List.of(detail));
        }
    }

    private void assertWithinOperationDay(List<Occupancy> shifted, Instant opDayEnd,
                                          long shiftMinutes, Section section) {
        for (Occupancy o : shifted) {
            if (o.endUtc().isAfter(opDayEnd)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "RESTRICTION_SHIFT_CONFLICT");
                detail.put("sectionId", o.sectionId());
                detail.put("shiftMinutesApplied", shiftMinutes);
                detail.put("minIntervalMinutes", section.minIntervalMinutes());
                detail.put("requiredEndUtc", o.endUtc().toString());
                detail.put("operationDayEndUtc", opDayEnd.toString());
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESTRICTION_SHIFT_CONFLICT",
                        "按线路最小间隔顺延后超出运营日末界，无法满足限速要求", List.of(detail));
            }
        }
    }

    private List<Occupancy> shiftAll(List<Occupancy> occupancies, int minutes) {
        long millis = minutes * 60_000L;
        return occupancies.stream()
                .map(o -> new Occupancy(o.id(), o.planId(), o.seq(), o.trainNo(), o.sectionId(),
                        o.startUtc().plusMillis(millis), o.endUtc().plusMillis(millis)))
                .toList();
    }

    private record Violation(Occupancy occupancy, SpeedRestriction restriction,
                             long overlapMinutes, double speedKmh) {
        AffectedSegment toAffected() {
            return new AffectedSegment(occupancy.trainNo(), occupancy.sectionId(),
                    restriction.restrictionKey(), restriction.version(), overlapMinutes,
                    speedKmh, restriction.maxSpeedKmh());
        }
    }
}
