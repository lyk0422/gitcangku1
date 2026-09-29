package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.WorkBlock;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.WorkBlockRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 时隙冲突检测：计划内同列车重叠、跨计划区段重叠、与已生效封锁窗口重叠，
 * 以及同一事务内多份新草稿之间的互相重叠。所有区间按 UTC 左闭右开比较，相邻端点不冲突。
 */
@Component
public class SlotConflictSupport {

    private final PlanRepository planRepo;
    private final WorkBlockRepository blockRepo;

    public SlotConflictSupport(PlanRepository planRepo, WorkBlockRepository blockRepo) {
        this.planRepo = planRepo;
        this.blockRepo = blockRepo;
    }

    /**
     * 本计划内同一列车的重叠占用检测（左闭右开，相邻合法）。
     */
    public List<Map<String, Object>> trainOverlaps(String scheduleKey, List<Occupancy> occupancies) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, List<Occupancy>> byTrain = new LinkedHashMap<>();
        for (Occupancy o : occupancies) {
            byTrain.computeIfAbsent(o.trainNo(), k -> new ArrayList<>()).add(o);
        }
        byTrain.forEach((trainNo, list) -> {
            List<Occupancy> sorted = list.stream()
                    .sorted(Comparator.comparing(Occupancy::startUtc)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                Occupancy prev = sorted.get(i - 1);
                Occupancy cur = sorted.get(i);
                if (cur.startUtc().isBefore(prev.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "TRAIN_OVERLAP");
                    detail.put("scheduleKey", scheduleKey);
                    detail.put("trainNo", trainNo);
                    detail.put("sectionId", cur.sectionId());
                    detail.put("startUtc", cur.startUtc().toString());
                    detail.put("endUtc", cur.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        });
        return conflicts;
    }

    /**
     * 与其他已发布计划在同日期、同区段上的重叠检测（左闭右开，相邻合法）。
     *
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；改签为新旧两个计划；
     *                       封锁登记/批量改签为本次全部旧计划与新草稿）
     */
    public List<Map<String, Object>> sectionConflicts(DayPlan plan, List<Occupancy> occupancies,
                                                      List<Long> excludePlanIds) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<PublishedSlot> published = planRepo.findPublishedSlots(plan.opDate(), sectionIds,
                excludePlanIds);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy o : occupancies) {
            for (PublishedSlot slot : published) {
                if (!o.sectionId().equals(slot.sectionId())) {
                    continue;
                }
                if (overlaps(o.startUtc(), o.endUtc(), slot.startUtc(), slot.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SECTION_CONFLICT");
                    detail.put("sectionId", o.sectionId());
                    detail.put("scheduleKey", plan.scheduleKey());
                    detail.put("conflictingScheduleKey", slot.scheduleKey());
                    detail.put("trainNo", o.trainNo());
                    detail.put("startUtc", o.startUtc().toString());
                    detail.put("endUtc", o.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    /**
     * 占用与同运营日全部已生效封锁窗口的重叠检测（左闭右开，相邻合法）。
     * 发布、恢复、改签、封锁登记内改签与批量改签前都必须调用。
     */
    public List<Map<String, Object>> blockConflicts(LocalDate opDate, String scheduleKey,
                                                    List<Occupancy> occupancies) {
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId)
                .collect(Collectors.toCollection(TreeSet::new))
                .stream().toList();
        List<WorkBlock> blocks = blockRepo.findActiveBlocks(opDate, sectionIds);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Occupancy o : occupancies) {
            for (WorkBlock block : blocks) {
                if (!o.sectionId().equals(block.sectionId())) {
                    continue;
                }
                if (overlaps(o.startUtc(), o.endUtc(), block.startUtc(), block.endUtc())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "BLOCK_CONFLICT");
                    detail.put("blockKey", block.blockKey());
                    detail.put("sectionId", o.sectionId());
                    detail.put("scheduleKey", scheduleKey);
                    detail.put("trainNo", o.trainNo());
                    detail.put("startUtc", o.startUtc().toString());
                    detail.put("endUtc", o.endUtc().toString());
                    detail.put("blockStartUtc", block.startUtc().toString());
                    detail.put("blockEndUtc", block.endUtc().toString());
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    /**
     * 同一事务内多份待发布新草稿之间的区段重叠检测（跨计划冲突，左闭右开）。
     */
    public List<Map<String, Object>> mutualConflicts(List<DraftGroup> drafts) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            for (int j = i + 1; j < drafts.size(); j++) {
                DraftGroup a = drafts.get(i);
                DraftGroup b = drafts.get(j);
                for (Occupancy oa : a.occupancies()) {
                    for (Occupancy ob : b.occupancies()) {
                        if (oa.sectionId().equals(ob.sectionId())
                                && overlaps(oa.startUtc(), oa.endUtc(), ob.startUtc(), ob.endUtc())) {
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("type", "SECTION_CONFLICT");
                            detail.put("sectionId", oa.sectionId());
                            detail.put("scheduleKey", a.scheduleKey());
                            detail.put("conflictingScheduleKey", b.scheduleKey());
                            detail.put("trainNo", oa.trainNo());
                            detail.put("startUtc", oa.startUtc().toString());
                            detail.put("endUtc", oa.endUtc().toString());
                            conflicts.add(detail);
                        }
                    }
                }
            }
        }
        return conflicts;
    }

    /**
     * 两个 UTC 区间是否按左闭右开相交：[s1,e1) 与 [s2,e2)，相邻端点不算。
     */
    public static boolean overlaps(Instant s1, Instant e1, Instant s2, Instant e2) {
        return s1.isBefore(e2) && s2.isBefore(e1);
    }

    /**
     * 批量裁决中的一份待发布新草稿。
     *
     * @param scheduleKey 新草稿业务键
     * @param occupancies 新草稿占用清单
     */
    public record DraftGroup(String scheduleKey, List<Occupancy> occupancies) {
    }
}
