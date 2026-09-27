package com.example.starter.plan.service;

import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.SegmentImpactView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 限速重排规划器（纯逻辑，无数据库访问，可独立单元测试）。
 *
 * <p>语义约定：计划占用时分视为按线路正常速度（normalSpeedKmh）运行；
 * 占用与生效限速令（限速低于正常速度）的叠加时段为受限运行时段，
 * 该时段按最低限速折算所需分钟数 = 实际分钟数 × 正常速度 / 最低限速（向上取整），
 * 所需大于实际即违反限速。违反时整张计划（全部占用段，不只单段）按线路最小间隔的
 * 整数倍整体顺延，直至不再违反；顺延超出运营日（左闭右开）则判定不可行并抛出 422。
 */
@Component
public class RearrangementPlanner {

    /** 顺延迭代上限（防御性约束；正常由运营日边界先触发）。 */
    private static final int MAX_SHIFTS = 2000;

    private final RailLineProperties line;

    public RearrangementPlanner(RailLineProperties line) {
        this.line = line;
    }

    /**
     * 重排结果。
     *
     * @param shiftMinutes        整体顺延分钟数，0 表示原计划时刻不违反限速
     * @param shiftedOccupancies  顺延后占用清单（shiftMinutes 为 0 时与原清单相同引用内容）
     * @param impacts             按原始计划时刻计算的逐段影响（业务证据，含实际/要求/差额）
     */
    public record ShiftOutcome(int shiftMinutes, List<Occupancy> shiftedOccupancies,
                               List<SegmentImpactView> impacts) {
    }

    /**
     * 计算计划在给定生效限速令下所需的整体顺延。
     *
     * @param occupancies     计划占用清单（原始时刻）
     * @param restrictions    相关区段上的生效限速令
     * @param dayEndExclusive 运营日结束时刻（不含），顺延不得使占用超出该时刻
     * @return 重排结果；shiftMinutes 为 0 表示无需顺延
     * @throws ApiException 顺延在运营日内不可行时抛出 422 REARRANGE_INFEASIBLE
     */
    public ShiftOutcome compute(List<Occupancy> occupancies, List<SpeedRestriction> restrictions,
                                Instant dayEndExclusive) {
        List<SegmentImpactView> impacts = impactsAt(occupancies, restrictions);
        if (!violates(impacts)) {
            return new ShiftOutcome(0, occupancies, impacts);
        }
        long shiftMillis = 0;
        for (int k = 1; k <= MAX_SHIFTS; k++) {
            shiftMillis = (long) k * line.minIntervalMinutes() * 60_000L;
            List<Occupancy> shifted = shift(occupancies, shiftMillis);
            boolean beyondDay = shifted.stream().anyMatch(o -> o.endUtc().isAfter(dayEndExclusive));
            if (beyondDay) {
                break;
            }
            if (!violates(impactsAt(shifted, restrictions))) {
                return new ShiftOutcome(k * line.minIntervalMinutes(), shifted, impacts);
            }
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REARRANGE_INFEASIBLE",
                "限速重排在运营日内不可行，计划保持原状态", infeasibleDetails(impacts));
    }

    /**
     * 计算给定占用清单在生效限速令下的逐段影响（按传入时刻原样计算，不做顺延）。
     */
    public List<SegmentImpactView> impactsAt(List<Occupancy> occupancies,
                                             List<SpeedRestriction> restrictions) {
        Map<String, List<SpeedRestriction>> bySection = new LinkedHashMap<>();
        for (SpeedRestriction r : restrictions) {
            bySection.computeIfAbsent(r.sectionId(), k -> new ArrayList<>()).add(r);
        }
        List<SegmentImpactView> result = new ArrayList<>(occupancies.size());
        for (Occupancy o : occupancies) {
            result.add(impactOf(o, bySection.getOrDefault(o.sectionId(), List.of())));
        }
        return result;
    }

    /**
     * 单段影响：将占用区间按覆盖限速令的边界切分，逐子区间取最低限速裁决。
     */
    private SegmentImpactView impactOf(Occupancy o, List<SpeedRestriction> sectionRestrictions) {
        List<SpeedRestriction> covering = sectionRestrictions.stream()
                .filter(r -> o.startUtc().isBefore(r.endUtc()) && r.startUtc().isBefore(o.endUtc()))
                .toList();
        if (covering.isEmpty()) {
            return new SegmentImpactView(o.seq(), o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc(),
                    0, 0, 0, null, List.of());
        }
        TreeSet<Instant> bounds = new TreeSet<>();
        bounds.add(o.startUtc());
        bounds.add(o.endUtc());
        for (SpeedRestriction r : covering) {
            bounds.add(max(r.startUtc(), o.startUtc()));
            bounds.add(min(r.endUtc(), o.endUtc()));
        }
        List<Instant> points = List.copyOf(bounds);
        long affectedMillis = 0;
        long requiredMillis = 0;
        Integer minLimit = null;
        TreeSet<Long> contributing = new TreeSet<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            Instant a = points.get(i);
            Instant b = points.get(i + 1);
            if (!a.isBefore(b)) {
                continue;
            }
            Instant mid = a.plusMillis(Duration.between(a, b).toMillis() / 2);
            int limit = line.normalSpeedKmh();
            List<SpeedRestriction> active = new ArrayList<>();
            for (SpeedRestriction r : covering) {
                if (!mid.isBefore(r.startUtc()) && mid.isBefore(r.endUtc())) {
                    active.add(r);
                }
            }
            if (active.isEmpty()) {
                continue;
            }
            for (SpeedRestriction r : active) {
                limit = Math.min(limit, r.maxSpeedKmh());
            }
            if (limit >= line.normalSpeedKmh()) {
                continue;
            }
            long lenMillis = Duration.between(a, b).toMillis();
            affectedMillis += lenMillis;
            // 所需毫秒 = 实际毫秒 × 正常速度 / 最低限速（向上取整）
            requiredMillis += ceilDiv(lenMillis * line.normalSpeedKmh(), limit);
            minLimit = minLimit == null ? limit : Math.min(minLimit, limit);
            for (SpeedRestriction r : active) {
                if (r.maxSpeedKmh() < line.normalSpeedKmh()) {
                    contributing.add(r.id());
                }
            }
        }
        long affectedMinutes = ceilDiv(affectedMillis, 60_000L);
        long requiredMinutes = ceilDiv(requiredMillis, 60_000L);
        return new SegmentImpactView(o.seq(), o.trainNo(), o.sectionId(), o.startUtc(), o.endUtc(),
                affectedMinutes, requiredMinutes, requiredMinutes - affectedMinutes,
                minLimit, List.copyOf(contributing));
    }

    private boolean violates(List<SegmentImpactView> impacts) {
        return impacts.stream().anyMatch(i -> i.deficitMinutes() > 0);
    }

    private List<Occupancy> shift(List<Occupancy> occupancies, long shiftMillis) {
        return occupancies.stream()
                .map(o -> new Occupancy(o.id(), o.planId(), o.seq(), o.trainNo(), o.sectionId(),
                        o.startUtc().plusMillis(shiftMillis), o.endUtc().plusMillis(shiftMillis)))
                .toList();
    }

    /**
     * 不可行 422 的稳定明细：逐段给出区段、原始时段、计划标识与受影响分钟数。
     */
    private List<Map<String, Object>> infeasibleDetails(List<SegmentImpactView> impacts) {
        List<Map<String, Object>> details = new ArrayList<>();
        for (SegmentImpactView i : impacts) {
            if (i.deficitMinutes() <= 0) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("type", "REARRANGE_INFEASIBLE");
            detail.put("sectionId", i.sectionId());
            detail.put("trainNo", i.trainNo());
            detail.put("startUtc", i.startUtc().toString());
            detail.put("endUtc", i.endUtc().toString());
            detail.put("affectedMinutes", i.affectedMinutes());
            detail.put("requiredMinutes", i.requiredMinutes());
            detail.put("deficitMinutes", i.deficitMinutes());
            detail.put("minLimitSpeedKmh", i.minLimitSpeedKmh());
            details.add(detail);
        }
        details.sort(Comparator.comparing(d -> (String) d.get("sectionId")));
        return details;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static long ceilDiv(long dividend, long divisor) {
        return (dividend + divisor - 1) / divisor;
    }
}
