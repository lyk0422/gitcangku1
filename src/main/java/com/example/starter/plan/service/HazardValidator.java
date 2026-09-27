package com.example.starter.plan.service;

import com.example.starter.plan.model.HazardCar;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.RestrictedSection;
import com.example.starter.plan.model.SeparationRule;
import com.example.starter.plan.repo.HazardRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * 危险品编组隔离与区段通行联合校验：在完整编组与完整区段路径上校验
 * 车厢位置重复、互斥类别最小间隔、限制区段开放清单与区段配置有效性。
 * 冲突明细均为结构化 Map，数量类字段返回实际值、要求值与差额。
 */
@Component
public class HazardValidator {

    private final HazardRepository hazardRepo;

    public HazardValidator(HazardRepository hazardRepo) {
        this.hazardRepo = hazardRepo;
    }

    /**
     * 联合校验：读取当前规则集与限制区段配置，校验完整编组与完整区段路径。
     *
     * @param scheduleKey 计划业务键（用于冲突明细）
     * @param occupancies 计划完整区段占用路径
     * @param cars        计划完整危险品编组
     * @return 冲突明细列表，空表示校验通过
     */
    public List<Map<String, Object>> validateAll(String scheduleKey, List<Occupancy> occupancies,
                                                 List<HazardCar> cars) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        conflicts.addAll(validateFormation(scheduleKey, cars, hazardRepo.findSeparationRules()));
        conflicts.addAll(validateSections(scheduleKey, occupancies, cars));
        return conflicts;
    }

    /**
     * 编组内校验：车厢位置重复与互斥类别最小间隔（位置差取绝对值，不足即冲突）。
     */
    public List<Map<String, Object>> validateFormation(String scheduleKey, List<HazardCar> cars,
                                                       List<SeparationRule> rules) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        // 车厢位置重复：按位置分组，组内多于一条即冲突
        Map<Integer, List<HazardCar>> byPosition = new TreeMap<>();
        for (HazardCar car : cars) {
            byPosition.computeIfAbsent(car.carPosition(), k -> new ArrayList<>()).add(car);
        }
        byPosition.forEach((position, group) -> {
            if (group.size() > 1) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "DUPLICATE_CAR_POSITION");
                detail.put("scheduleKey", scheduleKey);
                detail.put("carPosition", position);
                detail.put("occurrences", group.size());
                detail.put("hazardClasses",
                        group.stream().map(HazardCar::hazardClass).sorted().toList());
                conflicts.add(detail);
            }
        });
        // 互斥类别最小间隔：规则按规范化类别对索引
        Map<String, SeparationRule> ruleByPair = new LinkedHashMap<>();
        for (SeparationRule rule : rules) {
            ruleByPair.put(pairKey(rule.classA(), rule.classB()), rule);
        }
        List<HazardCar> sorted = cars.stream()
                .sorted(Comparator.comparingInt(HazardCar::carPosition)).toList();
        for (int i = 0; i < sorted.size(); i++) {
            for (int j = i + 1; j < sorted.size(); j++) {
                HazardCar a = sorted.get(i);
                HazardCar b = sorted.get(j);
                SeparationRule rule = ruleByPair.get(pairKey(a.hazardClass(), b.hazardClass()));
                if (rule == null) {
                    continue;
                }
                int actual = Math.abs(a.carPosition() - b.carPosition());
                if (actual < rule.minSeparation()) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", "SEPARATION_VIOLATION");
                    detail.put("scheduleKey", scheduleKey);
                    detail.put("classA", rule.classA());
                    detail.put("classB", rule.classB());
                    detail.put("carPositionA", a.carPosition());
                    detail.put("carPositionB", b.carPosition());
                    detail.put("actualSeparation", actual);
                    detail.put("requiredSeparation", rule.minSeparation());
                    detail.put("deficit", rule.minSeparation() - actual);
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    /**
     * 区段通行校验：完整区段路径上每个限制区段的开放清单必须覆盖编组全部类别；
     * 限制区段配置为空清单视为配置无效。
     */
    public List<Map<String, Object>> validateSections(String scheduleKey,
                                                      List<Occupancy> occupancies,
                                                      List<HazardCar> cars) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        List<String> sectionIds = occupancies.stream()
                .map(Occupancy::sectionId).distinct().sorted().toList();
        for (String sectionId : sectionIds) {
            var restricted = hazardRepo.findRestrictedSection(sectionId);
            if (restricted.isEmpty()) {
                continue;
            }
            RestrictedSection config = restricted.get();
            if (config.allowedClasses().isEmpty()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "SECTION_CONFIG_INVALID");
                detail.put("scheduleKey", scheduleKey);
                detail.put("sectionId", sectionId);
                detail.put("reason", "限制区段开放类别清单为空");
                conflicts.add(detail);
                continue;
            }
            // 同一类别在同一区段聚合为一条冲突，列出涉及车厢位置
            Map<String, List<Integer>> deniedPositions = new TreeMap<>();
            for (HazardCar car : cars) {
                if (!config.allowedClasses().contains(car.hazardClass())) {
                    deniedPositions.computeIfAbsent(car.hazardClass(), k -> new ArrayList<>())
                            .add(car.carPosition());
                }
            }
            deniedPositions.forEach((hazardClass, positions) -> {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "SECTION_CLASS_NOT_ALLOWED");
                detail.put("scheduleKey", scheduleKey);
                detail.put("sectionId", sectionId);
                detail.put("hazardClass", hazardClass);
                detail.put("carPositions", positions.stream().sorted().toList());
                detail.put("allowedClasses", config.allowedClasses());
                conflicts.add(detail);
            });
        }
        return conflicts;
    }

    /**
     * 类别对规范化键：字典序较小者在前，长度前缀拼接避免类别名拼接歧义。
     */
    public static String pairKey(String classX, String classY) {
        String first = classX.compareTo(classY) <= 0 ? classX : classY;
        String second = classX.compareTo(classY) <= 0 ? classY : classX;
        return first.length() + ":" + first + second;
    }
}
