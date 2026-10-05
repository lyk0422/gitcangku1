package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.SectionProfile;
import com.example.starter.plan.model.SpeedRestriction;
import com.example.starter.plan.repo.RestrictionRepository;
import com.example.starter.plan.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 气象限速判定与整体顺延计算。
 *
 * <p>判定规则：区段已登记档案且计划占用与生效限速令时段（UTC 左闭右开）重叠，
 * 且限速令最高速度低于线路既有设计速度时，原计划违反限速；同一区段同一时刻多条限速令叠加，
 * 以最低速度为准（低于设计速度的才构成违反）。
 *
 * <p>顺延规则：以违反区段的线路既有最小间隔为步进整体后移整张计划的全部占用（不只调整单段），
 * 直至不再违反任何生效限速令；已开始运行的计划不可重排，顺延导致占用越出运营日或步进次数超限
 * 均返回 422。
 */
@Component
public class SlotRearranger {

    /** 顺延步进次数上限，防止异常数据导致死循环。 */
    static final int MAX_SHIFT_STEPS = 200;

    /**
     * 一条限速违反记录。
     *
     * @param restriction       被违反的限速令
     * @param overlapMinutes    原计划占用与限速令时段的重叠分钟数（受影响运行分钟数）
     * @param minHeadwayMinutes 该线路既有最小间隔（顺延步进单位）
     */
    public record Violation(SpeedRestriction restriction, long overlapMinutes,
                            int minHeadwayMinutes) {
    }

    /**
     * 顺延计算结果。
     *
     * @param shiftMinutes       整体顺延分钟数，0 表示原计划未违反限速
     * @param shiftedOccupancies 顺延后的占用清单（seq 与原占用一致）
     * @param violations         原计划时刻下的限速违反记录（用于固化重排快照）
     */
    public record ShiftOutcome(long shiftMinutes, List<Occupancy> shiftedOccupancies,
                               List<Violation> violations) {
    }

    private final RestrictionRepository restrictionRepo;

    public SlotRearranger(RestrictionRepository restrictionRepo) {
        this.restrictionRepo = restrictionRepo;
    }

    /**
     * 计算指定计划占用在生效限速令下需要的整体顺延。
     *
     * @param plan        计划（提供运营日与业务键）
     * @param occupancies 计划当前占用清单
     * @param now         当前时刻（判定计划是否已开始运行）
     * @return 顺延结果；shiftMinutes 为 0 时 shiftedOccupancies 与原清单相等
     * @throws ApiException 422：计划已开始运行不可重排 / 顺延越出运营日 / 步进次数超限
     */
    public ShiftOutcome computeShift(DayPlan plan, List<Occupancy> occupancies, Instant now) {
        long shiftMinutes = 0;
        List<Occupancy> current = occupancies;
        for (int step = 0; step < MAX_SHIFT_STEPS; step++) {
            List<Violation> violations = findViolations(current);
            if (violations.isEmpty()) {
                List<Violation> originalViolations = shiftMinutes == 0
                        ? List.of() : findViolations(occupancies);
                return new ShiftOutcome(shiftMinutes, current, originalViolations);
            }
            if (shiftMinutes == 0 && isStarted(occupancies, now)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "PLAN_ALREADY_RUNNING");
                detail.put("scheduleKey", plan.scheduleKey());
                detail.put("firstStartUtc", firstStart(occupancies).toString());
                detail.put("nowUtc", now.toString());
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PLAN_ALREADY_RUNNING",
                        "计划已开始运行，不可按限速重排: " + plan.scheduleKey(), List.of(detail));
            }
            int stepMinutes = violations.stream()
                    .mapToInt(Violation::minHeadwayMinutes)
                    .max().orElse(0);
            shiftMinutes += stepMinutes;
            current = shift(occupancies, shiftMinutes);
            assertWithinOperationDay(plan, current, shiftMinutes);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "REARRANGE_LIMIT_EXCEEDED");
        detail.put("scheduleKey", plan.scheduleKey());
        detail.put("maxShiftSteps", MAX_SHIFT_STEPS);
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REARRANGE_LIMIT_EXCEEDED",
                "限速顺延步进次数超限: " + plan.scheduleKey(), List.of(detail));
    }

    /**
     * 原计划时刻下被违反的限速令快照（去重后用于固化重排记录）。
     */
    public List<Violation> findViolations(List<Occupancy> occupancies) {
        List<Violation> violations = new ArrayList<>();
        for (Occupancy o : occupancies) {
            Optional<SectionProfile> profile = restrictionRepo.findSection(o.sectionId());
            if (profile.isEmpty()) {
                continue;
            }
            List<SpeedRestriction> restrictions = restrictionRepo.findActiveOverlapping(
                    o.sectionId(), o.startUtc(), o.endUtc());
            for (SpeedRestriction r : restrictions) {
                if (r.maxSpeedKmh() >= profile.get().designSpeedKmh()) {
                    continue;
                }
                Instant overlapStart = o.startUtc().isAfter(r.startUtc()) ? o.startUtc() : r.startUtc();
                Instant overlapEnd = o.endUtc().isBefore(r.endUtc()) ? o.endUtc() : r.endUtc();
                long overlapMinutes = Duration.between(overlapStart, overlapEnd).toMinutes();
                violations.add(new Violation(r, overlapMinutes, profile.get().minHeadwayMinutes()));
            }
        }
        return violations;
    }

    /**
     * 计划占用涉及区段上全部生效限速令的稳定指纹（key@version 排序拼接），
     * 作为发布/改签幂等指纹的限速版本成分。
     */
    public String restrictionDigest(List<Occupancy> occupancies) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<SpeedRestriction> active = restrictionRepo.findActiveBySections(sectionIds);
        StringBuilder sb = new StringBuilder();
        for (SpeedRestriction r : active) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(r.restrictionKey()).append('@').append(r.version());
        }
        return sb.toString();
    }

    /**
     * 整体后移占用清单（全部占用平移相同分钟数，保持 seq 与时长）。
     */
    private List<Occupancy> shift(List<Occupancy> occupancies, long shiftMinutes) {
        Duration shift = Duration.ofMinutes(shiftMinutes);
        List<Occupancy> result = new ArrayList<>(occupancies.size());
        for (Occupancy o : occupancies) {
            result.add(new Occupancy(o.id(), o.planId(), o.seq(), o.trainNo(), o.sectionId(),
                    o.startUtc().plus(shift), o.endUtc().plus(shift)));
        }
        return result;
    }

    /**
     * 计划是否已开始运行：任一占用开始时刻不晚于当前时刻。
     */
    private boolean isStarted(List<Occupancy> occupancies, Instant now) {
        return occupancies.stream().anyMatch(o -> !o.startUtc().isAfter(now));
    }

    private Instant firstStart(List<Occupancy> occupancies) {
        return occupancies.stream().map(Occupancy::startUtc).min(Instant::compareTo)
                .orElseThrow(() -> new IllegalStateException("占用清单为空"));
    }

    /**
     * 顺延后占用必须仍落在计划运营日（Asia/Shanghai）内，否则 422 并返回实际值与要求值。
     */
    private void assertWithinOperationDay(DayPlan plan, List<Occupancy> shifted, long shiftMinutes) {
        ZoneId zone = PlanService.OPERATION_ZONE;
        LocalDate opDate = plan.opDate();
        Instant dayStart = opDate.atStartOfDay(zone).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(zone).toInstant();
        for (Occupancy o : shifted) {
            if (o.startUtc().isBefore(dayStart) || o.endUtc().isAfter(dayEnd)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "REARRANGE_OUT_OF_DAY");
                detail.put("scheduleKey", plan.scheduleKey());
                detail.put("sectionId", o.sectionId());
                detail.put("trainNo", o.trainNo());
                detail.put("shiftMinutes", shiftMinutes);
                detail.put("actualStartUtc", o.startUtc().toString());
                detail.put("actualEndUtc", o.endUtc().toString());
                detail.put("requiredDayStartUtc", dayStart.toString());
                detail.put("requiredDayEndUtc", dayEnd.toString());
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REARRANGE_OUT_OF_DAY",
                        "按限速顺延后占用越出运营日 " + opDate + ": " + plan.scheduleKey(),
                        List.of(detail));
            }
        }
    }
}
