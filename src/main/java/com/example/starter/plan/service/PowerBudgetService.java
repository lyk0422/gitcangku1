package com.example.starter.plan.service;

import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PowerSlot;
import com.example.starter.plan.model.PowerSnapshot;
import com.example.starter.plan.model.SectionBinding;
import com.example.starter.plan.model.Substation;
import com.example.starter.plan.repo.SubstationRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * 供电预算裁决：将已发布与候选占用的起止合并为恒定时间片，逐片累计功率（精确整数），
 * 任一片超过变电所上限即判超限；同一列车不同占用分别累计。
 * 发布/改签成功后冻结当时绑定、功率与上限版本为快照，历史查询不漂移。
 */
@Service
public class PowerBudgetService {

    private final SubstationRepository substationRepo;

    public PowerBudgetService(SubstationRepository substationRepo) {
        this.substationRepo = substationRepo;
    }

    /**
     * 候选占用（发布/改签的新计划）相对已发布计划的功率超限检测。
     * 仅候选占用落在已绑定区段时参与；未绑定区段保持旧模式不计。
     *
     * @param opDate         运营日
     * @param candidates     候选占用清单
     * @param excludePlanIds 检测时排除的计划 id（发布为自身；改签为新旧两个计划）
     * @return 超限时间片明细列表，空表示未超限
     */
    public List<Map<String, Object>> findViolations(LocalDate opDate, List<Occupancy> candidates,
                                                    List<Long> excludePlanIds) {
        Map<Long, Substation> substations = new LinkedHashMap<>();
        Map<Long, List<PowerSlot>> candidateSlots = new LinkedHashMap<>();
        for (Occupancy o : candidates) {
            Optional<SectionBinding> binding = substationRepo.findBinding(o.sectionId());
            if (binding.isEmpty()) {
                continue;
            }
            Substation substation = substationRepo.findById(binding.get().substationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "绑定指向的变电所缺失: " + binding.get()));
            substations.putIfAbsent(substation.id(), substation);
            candidateSlots.computeIfAbsent(substation.id(), k -> new ArrayList<>())
                    .add(new PowerSlot(o.sectionId(), o.startUtc(), o.endUtc(), o.powerKw()));
        }
        List<Map<String, Object>> violations = new ArrayList<>();
        substations.forEach((substationId, substation) -> {
            List<String> sections = substationRepo.findSectionsBySubstation(substationId);
            List<PowerSlot> all = new ArrayList<>(
                    substationRepo.findPublishedPowerSlots(opDate, sections, excludePlanIds));
            all.addAll(candidateSlots.get(substationId));
            violations.addAll(sweep(substation, all));
        });
        return violations;
    }

    /**
     * 既有已发布计划相对给定上限的超限检测（调低上限/改绑裁决用，不限运营日）。
     *
     * @param substation 目标变电所（上限取其当前值或拟调整值）
     * @param limitKw    参与裁决的功率上限（千瓦）
     * @param slots      参与累计的已发布功率时隙
     * @return 超限时间片明细列表，空表示未超限
     */
    public List<Map<String, Object>> findViolations(Substation substation, int limitKw,
                                                    List<PowerSlot> slots) {
        return sweep(new Substation(substation.id(), substation.substationKey(), limitKw,
                substation.version()), slots);
    }

    /**
     * 合并全部时隙起止点，逐恒定时间片累计功率，超过上限的片输出明细。
     * 累计使用 long 精确整数，范围内无溢出。
     */
    private List<Map<String, Object>> sweep(Substation substation, List<PowerSlot> slots) {
        TreeSet<Long> points = new TreeSet<>();
        for (PowerSlot s : slots) {
            points.add(s.startUtc().toEpochMilli());
            points.add(s.endUtc().toEpochMilli());
        }
        List<Map<String, Object>> violations = new ArrayList<>();
        List<Long> sorted = List.copyOf(points);
        for (int i = 0; i + 1 < sorted.size(); i++) {
            long from = sorted.get(i);
            long to = sorted.get(i + 1);
            long total = 0;
            for (PowerSlot s : slots) {
                if (s.startUtc().toEpochMilli() <= from && s.endUtc().toEpochMilli() >= to) {
                    total += s.powerKw();
                }
            }
            if (total > substation.powerLimitKw()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "POWER_LIMIT_EXCEEDED");
                detail.put("substationKey", substation.substationKey());
                detail.put("startUtc", Instant.ofEpochMilli(from).toString());
                detail.put("endUtc", Instant.ofEpochMilli(to).toString());
                detail.put("limitKw", substation.powerLimitKw());
                detail.put("actualKw", total);
                violations.add(detail);
            }
        }
        return violations;
    }

    /**
     * 发布/改签裁决通过后冻结快照：对落在已绑定区段的每条占用，
     * 记录当时绑定变电所、声明功率与上限版本。须在发布锁与事务内调用。
     */
    public void freezeSnapshots(long planId, List<Occupancy> occupancies) {
        List<PowerSnapshot> snapshots = new ArrayList<>();
        for (Occupancy o : occupancies) {
            Optional<SectionBinding> binding = substationRepo.findBinding(o.sectionId());
            if (binding.isEmpty()) {
                continue;
            }
            Substation substation = substationRepo.findById(binding.get().substationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "绑定指向的变电所缺失: " + binding.get()));
            snapshots.add(new PowerSnapshot(0L, planId, o.seq(), o.sectionId(), substation.id(),
                    substation.substationKey(), substation.version(), substation.powerLimitKw(),
                    o.powerKw(), o.startUtc(), o.endUtc()));
        }
        if (!snapshots.isEmpty()) {
            substationRepo.insertPowerSnapshots(snapshots, System.currentTimeMillis());
        }
    }

    /**
     * 查询计划冻结的功率快照。
     */
    public List<PowerSnapshot> findSnapshots(long planId) {
        return substationRepo.findSnapshots(planId);
    }
}
