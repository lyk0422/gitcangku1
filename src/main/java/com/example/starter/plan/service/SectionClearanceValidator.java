package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.SectionConfig;
import com.example.starter.plan.model.SectionSlot;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 单线区段换向间隔校验：按区段把已发布与候选占用合并，按开始时刻排序后校验全部相邻对。
 * 任意相邻对重叠即互斥冲突（左闭右开，相邻合法）；方向相反的相邻对还要求
 * 后者开始减前者结束不小于配置的清空间隔（整秒）。候选内部相邻对同样检查。
 */
@Component
public class SectionClearanceValidator {

    /** 合并校验用的占用视图。 */
    private record SlotView(String scheduleKey, String trainNo, String direction,
                            Instant startUtc, Instant endUtc) {
    }

    private final PlanRepository planRepo;

    public SectionClearanceValidator(PlanRepository planRepo) {
        this.planRepo = planRepo;
    }

    /**
     * 发布/改签时校验候选计划在已配置区段上的占用：先做候选方向预校验，
     * 再把该区段已发布（排除指定计划）与候选占用合并排序，校验全部相邻对。
     * 已发布集合中检出无方向占用时抛 409（不能猜方向，须先取消）。
     *
     * @return 422 冲突明细列表（为空表示通过）
     */
    public List<Map<String, Object>> validateCandidate(DayPlan plan, List<Occupancy> occupancies,
                                                       Map<String, SectionConfig> configs,
                                                       List<Long> excludePlanIds) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, List<Occupancy>> candidateBySection = new LinkedHashMap<>();
        for (Occupancy o : occupancies) {
            if (configs.containsKey(o.sectionId())) {
                candidateBySection.computeIfAbsent(o.sectionId(), k -> new ArrayList<>()).add(o);
            }
        }
        if (candidateBySection.isEmpty()) {
            return conflicts;
        }
        // 候选方向预校验：有配置区段必须给出且属于正/反方向之一
        candidateBySection.forEach((sectionId, list) -> {
            SectionConfig config = configs.get(sectionId);
            for (Occupancy o : list) {
                if (o.direction() == null || o.direction().isBlank()) {
                    Map<String, Object> detail = baseDetail("DIRECTION_REQUIRED", sectionId,
                            plan.scheduleKey(), o.trainNo());
                    detail.put("startUtc", o.startUtc().toString());
                    detail.put("endUtc", o.endUtc().toString());
                    conflicts.add(detail);
                } else if (!config.allows(o.direction())) {
                    Map<String, Object> detail = baseDetail("DIRECTION_UNKNOWN", sectionId,
                            plan.scheduleKey(), o.trainNo());
                    detail.put("direction", o.direction());
                    detail.put("allowedForward", config.directionForward());
                    detail.put("allowedBackward", config.directionBackward());
                    conflicts.add(detail);
                }
            }
        });
        if (!conflicts.isEmpty()) {
            return conflicts;
        }
        List<PublishedSlot> published = planRepo.findPublishedSlots(plan.opDate(),
                candidateBySection.keySet(), excludePlanIds);
        for (PublishedSlot s : published) {
            if (configs.containsKey(s.sectionId())
                    && (s.direction() == null || s.direction().isBlank())) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_DIRECTION_UNKNOWN",
                        "区段 " + s.sectionId() + " 存在无方向已发布占用（计划 " + s.scheduleKey()
                                + "），不能猜方向，须先取消该计划");
            }
        }
        candidateBySection.forEach((sectionId, list) -> {
            SectionConfig config = configs.get(sectionId);
            List<SlotView> merged = new ArrayList<>();
            for (Occupancy o : list) {
                merged.add(new SlotView(plan.scheduleKey(), o.trainNo(), o.direction(),
                        o.startUtc(), o.endUtc()));
            }
            for (PublishedSlot s : published) {
                if (s.sectionId().equals(sectionId)) {
                    merged.add(new SlotView(s.scheduleKey(), s.trainNo(), s.direction(),
                            s.startUtc(), s.endUtc()));
                }
            }
            conflicts.addAll(checkAdjacentPairs(sectionId, config.clearanceSeconds(), merged));
        });
        return conflicts;
    }

    /**
     * 配置创建/更新时验证该区段当前已发布集合在新配置下合法：无方向占用或方向不属于
     * 新方向集、任一运营日内相邻对违反互斥或清空间隔，均抛 409。
     */
    public void validatePublishedSet(String sectionId, SectionConfig newConfig) {
        List<SectionSlot> slots = planRepo.findPublishedSlotsBySection(sectionId);
        for (SectionSlot s : slots) {
            if (s.direction() == null || s.direction().isBlank()) {
                throw new ApiException(HttpStatus.CONFLICT, "OCCUPANCY_DIRECTION_UNKNOWN",
                        "区段 " + sectionId + " 存在无方向已发布占用（计划 " + s.scheduleKey()
                                + "），不能猜方向，须先取消该计划");
            }
            if (!newConfig.allows(s.direction())) {
                throw new ApiException(HttpStatus.CONFLICT, "CONFIG_SET_INVALID",
                        "已发布占用方向 " + s.direction() + " 不属于新配置方向集（计划 "
                                + s.scheduleKey() + "）");
            }
        }
        Map<LocalDate, List<SlotView>> byDay = new LinkedHashMap<>();
        for (SectionSlot s : slots) {
            byDay.computeIfAbsent(s.opDate(), k -> new ArrayList<>())
                    .add(new SlotView(s.scheduleKey(), s.trainNo(), s.direction(),
                            s.startUtc(), s.endUtc()));
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        byDay.forEach((day, daySlots) -> conflicts.addAll(
                checkAdjacentPairs(sectionId, newConfig.clearanceSeconds(), daySlots)));
        if (!conflicts.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFIG_SET_INVALID",
                    "新配置下当前已发布集合非法，配置未生效", conflicts);
        }
    }

    /**
     * 排序后校验全部相邻对：重叠为互斥冲突；反向相邻间隔不足为清空冲突（含实际秒数与要求值）。
     */
    private List<Map<String, Object>> checkAdjacentPairs(String sectionId, int clearanceSeconds,
                                                         List<SlotView> slots) {
        List<SlotView> sorted = slots.stream()
                .sorted(Comparator.comparing(SlotView::startUtc).thenComparing(SlotView::endUtc))
                .toList();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            SlotView prev = sorted.get(i - 1);
            SlotView cur = sorted.get(i);
            if (cur.startUtc().isBefore(prev.endUtc())) {
                Map<String, Object> detail = baseDetail("SECTION_CONFLICT", sectionId,
                        cur.scheduleKey(), cur.trainNo());
                detail.put("conflictingScheduleKey", prev.scheduleKey());
                detail.put("startUtc", cur.startUtc().toString());
                detail.put("endUtc", cur.endUtc().toString());
                conflicts.add(detail);
            } else if (!prev.direction().equals(cur.direction())) {
                long gapSeconds = Duration.between(prev.endUtc(), cur.startUtc()).getSeconds();
                if (gapSeconds < clearanceSeconds) {
                    Map<String, Object> detail = baseDetail("CLEARANCE_CONFLICT", sectionId,
                            cur.scheduleKey(), cur.trainNo());
                    detail.put("prevScheduleKey", prev.scheduleKey());
                    detail.put("prevDirection", prev.direction());
                    detail.put("prevEndUtc", prev.endUtc().toString());
                    detail.put("nextDirection", cur.direction());
                    detail.put("nextStartUtc", cur.startUtc().toString());
                    detail.put("actualGapSeconds", gapSeconds);
                    detail.put("requiredClearanceSeconds", clearanceSeconds);
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    private Map<String, Object> baseDetail(String type, String sectionId, String scheduleKey,
                                           String trainNo) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("sectionId", sectionId);
        detail.put("scheduleKey", scheduleKey);
        detail.put("trainNo", trainNo);
        return detail;
    }
}
