package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.RearrangeRecord;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.repo.RestrictionRepository;
import com.example.starter.plan.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 气象限速重排：发布或改签计划时，逐段计算原运行时刻在限速时段内的受影响分钟数；
 * 若违反限速，按线路既有最小间隔将整张计划（全部区段占用）整体顺延，
 * 直至不再违反任何生效限速令，并生成固化限速令版本快照的重排记录。
 *
 * <p>已开始运行的计划（首条占用开始时刻不晚于当前时刻）不可重排，
 * 限速令只影响后续发布或改签；重排判定统一走注入的 {@link Clock}。
 */
@Service
public class RearrangeService {

    /** 线路既有最小间隔（分钟）：整体顺延的步长。 */
    public static final int LINE_MIN_INTERVAL_MINUTES = 5;

    /** 单计划最大顺延步数（5 分钟 × 288 = 24 小时），超出判定无法满足限速。 */
    private static final int MAX_SHIFTS = 288;

    private final RestrictionRepository restrictionRepo;
    private final Clock clock;

    public RearrangeService(RestrictionRepository restrictionRepo, Clock clock) {
        this.restrictionRepo = restrictionRepo;
        this.clock = clock;
    }

    /**
     * 重排结果：effective 为最终生效占用（未顺延则与原占用相同）；
     * records 为待追加的重排记录（空表示未发生顺延）。
     */
    public record ShiftResult(List<Occupancy> occupancies, List<RearrangeRecord> records) {

        /**
         * 是否发生了整体顺延。
         */
        public boolean shifted() {
            return !records.isEmpty();
        }
    }

    /**
     * 评估并按需整体顺延计划占用。本方法只计算不落库，
     * 由调用方在全部校验通过后的同一事务内统一持久化。
     *
     * @param plan        待发布/改签发布的计划
     * @param occupancies 计划当前占用（原运行时刻）
     * @param nowMillis   事务内统一时间戳（UTC 毫秒），用于重排记录创建时刻
     */
    public ShiftResult evaluateShift(DayPlan plan, List<Occupancy> occupancies, long nowMillis) {
        ShiftResult unchanged = new ShiftResult(occupancies, List.of());
        Instant firstStart = occupancies.stream()
                .map(Occupancy::startUtc).min(Instant::compareTo).orElse(clock.instant());
        // 已开始运行的计划不可重排
        if (!firstStart.isAfter(clock.instant())) {
            return unchanged;
        }
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId).distinct().sorted().toList();
        List<SpeedRestriction> active = restrictionRepo.findActiveBySections(sectionIds);
        if (active.isEmpty()) {
            return unchanged;
        }
        Instant dayEnd = plan.opDate().plusDays(1)
                .atStartOfDay(PlanService.OPERATION_ZONE).toInstant();
        List<Occupancy> current = occupancies;
        int shifts = 0;
        while (hasViolation(current, active)) {
            if (shifts >= MAX_SHIFTS) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESTRICTION_UNRESOLVABLE",
                        "按最小间隔顺延 " + MAX_SHIFTS + " 步后仍违反限速，计划保持原状态",
                        List.of(Map.of("scheduleKey", plan.scheduleKey(),
                                "shiftAttempts", MAX_SHIFTS,
                                "stepMinutes", LINE_MIN_INTERVAL_MINUTES,
                                "maxShiftMinutes", MAX_SHIFTS * LINE_MIN_INTERVAL_MINUTES)));
            }
            current = shiftAll(current);
            shifts++;
            for (Occupancy o : current) {
                if (o.endUtc().isAfter(dayEnd)) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("seq", o.seq());
                    detail.put("trainNo", o.trainNo());
                    detail.put("sectionId", o.sectionId());
                    detail.put("shiftedEndUtc", o.endUtc().toString());
                    detail.put("operationDayEndUtc", dayEnd.toString());
                    detail.put("excessMinutes",
                            java.time.Duration.between(dayEnd, o.endUtc()).toMinutes());
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "SHIFT_EXCEEDS_OPERATION_DAY",
                            "限速顺延超出运营日 " + plan.opDate() + "，计划保持原状态",
                            List.of(detail));
                }
            }
        }
        if (shifts == 0) {
            return unchanged;
        }
        int shiftMinutes = shifts * LINE_MIN_INTERVAL_MINUTES;
        List<RearrangeRecord> records =
                buildRecords(plan, occupancies, active, shiftMinutes, nowMillis);
        return new ShiftResult(current, records);
    }

    /**
     * 是否存在任一占用违反任一生效限速令（同区段且时段相交，左闭右开）。
     */
    private boolean hasViolation(List<Occupancy> occupancies, List<SpeedRestriction> active) {
        for (Occupancy o : occupancies) {
            for (SpeedRestriction r : active) {
                if (overlaps(o, r)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean overlaps(Occupancy o, SpeedRestriction r) {
        return o.sectionId().equals(r.sectionId())
                && o.startUtc().isBefore(r.endUtc())
                && r.startUtc().isBefore(o.endUtc());
    }

    /**
     * 整张计划全部占用按线路最小间隔顺延一步。
     */
    private List<Occupancy> shiftAll(List<Occupancy> occupancies) {
        List<Occupancy> shifted = new ArrayList<>(occupancies.size());
        for (Occupancy o : occupancies) {
            shifted.add(new Occupancy(o.id(), o.planId(), o.seq(), o.trainNo(), o.sectionId(),
                    o.startUtc().plusSeconds(LINE_MIN_INTERVAL_MINUTES * 60L),
                    o.endUtc().plusSeconds(LINE_MIN_INTERVAL_MINUTES * 60L)));
        }
        return shifted;
    }

    /**
     * 基于原计划时刻生成重排记录：逐段计算受影响运行分钟数，
     * 固化触发重排的限速令版本与参数快照、原计划时刻与新计划时刻。
     */
    private List<RearrangeRecord> buildRecords(DayPlan plan, List<Occupancy> originals,
                                               List<SpeedRestriction> active,
                                               int shiftMinutes, long nowMillis) {
        List<RearrangeRecord> records = new ArrayList<>();
        for (Occupancy o : originals) {
            for (SpeedRestriction r : active) {
                if (!overlaps(o, r)) {
                    continue;
                }
                long overlapMillis = java.time.Duration.between(
                        o.startUtc().isAfter(r.startUtc()) ? o.startUtc() : r.startUtc(),
                        o.endUtc().isBefore(r.endUtc()) ? o.endUtc() : r.endUtc()).toMillis();
                records.add(new RearrangeRecord(0L, plan.id(), plan.scheduleKey(),
                        r.restrictionKey(), r.version(), r.sectionId(), r.maxSpeedKmh(),
                        r.startUtc(), r.endUtc(), o.seq(), o.trainNo(),
                        o.startUtc(), o.endUtc(),
                        o.startUtc().plusSeconds(shiftMinutes * 60L),
                        o.endUtc().plusSeconds(shiftMinutes * 60L),
                        shiftMinutes, (int) (overlapMillis / 60000L), nowMillis));
            }
        }
        return records;
    }
}