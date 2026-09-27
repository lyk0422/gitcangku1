package com.example.starter.plan.service;

import com.example.starter.plan.model.HazardRule;
import com.example.starter.plan.web.dto.HazardRuleRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 危险品隔离纯逻辑校验（无 Spring/DB 依赖，可独立单元测试）。
 *
 * <p>三类检查：
 * <ul>
 *   <li>编组位置重复（CONSIST_POSITION_DUPLICATE）；</li>
 *   <li>互斥类别间隔：同编组内两类危险品车厢的位置距离 |pA-pB|（单位：节车厢）
 *       必须不小于规则要求（HAZARD_SEPARATION，输出实际/要求/差额）；</li>
 *   <li>限制区段授权：完整区段路径上已配置开放清单的区段，编组内每个危险品类别
 *       必须在清单内（HAZARD_UNAUTHORIZED）。</li>
 * </ul>
 * 规则集与区段配置自身校验产出 RULE 类、SECTION 类冲突；所有输出列表排序稳定。
 */
public final class HazardValidation {

    private HazardValidation() {
    }

    /**
     * 编组车厢（位置 + 可空危险品类别，null 表示普通车厢）。
     */
    public record Car(int position, String hazardClass) {
    }

    /**
     * 规则集规范化结果。
     *
     * @param rules     规范化后的规则（类别对字母序、按类别对排序、完全相同的重复对去重）
     * @param conflicts 配置冲突（同类别对要求不一致等）；非空时配置不得落库
     */
    public record RulesNormalization(List<HazardRule> rules, List<Map<String, Object>> conflicts) {
    }

    /**
     * 规范化规则集：类别对按字母序排列；完全相同的规则去重；
     * 同类别对要求不一致报 RULE_PAIR_CONFLICT；同类别互斥报 RULE_SAME_CLASS。
     */
    public static RulesNormalization normalizeRules(List<HazardRuleRequest> raw) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        Map<String, HazardRule> byPair = new TreeMap<>();
        for (int i = 0; i < raw.size(); i++) {
            HazardRuleRequest r = raw.get(i);
            int index = i;
            if (r.classA().equals(r.classB())) {
                conflicts.add(detail("RULE_SAME_CLASS", d -> {
                    d.put("index", index);
                    d.put("classA", r.classA());
                    d.put("classB", r.classB());
                    d.put("reason", "互斥类别对不能是同一类别");
                }));
                continue;
            }
            HazardRule rule = HazardRule.ordered(r.classA(), r.classB(), r.minSeparationCars());
            HazardRule existing = byPair.get(rule.classA() + "\u0001" + rule.classB());
            if (existing == null) {
                byPair.put(rule.classA() + "\u0001" + rule.classB(), rule);
            } else if (existing.minSeparationCars() != rule.minSeparationCars()) {
                int requiredIndex = index;
                conflicts.add(detail("RULE_PAIR_CONFLICT", d -> {
                    d.put("classA", rule.classA());
                    d.put("classB", rule.classB());
                    d.put("requiredCars", existing.minSeparationCars());
                    d.put("conflictingRequiredCars", rule.minSeparationCars());
                    d.put("index", requiredIndex);
                    d.put("reason", "同一类别对配置了不同的最小间隔车厢数");
                }));
            }
            // 完全相同的重复规则视为去重，不报冲突
        }
        return new RulesNormalization(List.copyOf(byPair.values()), conflicts);
    }

    /**
     * 校验开放清单自身：类别重复报 SECTION_CLASS_DUPLICATE。
     * 返回去重后按字母序排列的清单（调用方仅在冲突为空时使用）。
     */
    public static List<String> normalizeAllowedClasses(List<String> raw,
                                                       List<Map<String, Object>> conflicts) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicated = new LinkedHashSet<>();
        for (String c : raw) {
            if (!seen.add(c)) {
                duplicated.add(c);
            }
        }
        for (String c : duplicated) {
            conflicts.add(detail("SECTION_CLASS_DUPLICATE", d -> {
                d.put("hazardClass", c);
                d.put("reason", "开放清单中危险品类别重复");
            }));
        }
        return seen.stream().sorted().toList();
    }

    /**
     * 编组位置重复检查，重复位置列出全部登记序号对（按 (position, firstIndex, otherIndex) 排序）。
     */
    public static List<Map<String, Object>> findDuplicatePositions(List<Car> cars) {
        Map<Integer, List<Integer>> indexesByPosition = new TreeMap<>();
        for (int i = 0; i < cars.size(); i++) {
            indexesByPosition.computeIfAbsent(cars.get(i).position(), k -> new ArrayList<>()).add(i);
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : indexesByPosition.entrySet()) {
            List<Integer> indexes = entry.getValue();
            if (indexes.size() < 2) {
                continue;
            }
            for (int a = 0; a < indexes.size(); a++) {
                for (int b = a + 1; b < indexes.size(); b++) {
                    int firstIndex = indexes.get(a);
                    int otherIndex = indexes.get(b);
                    conflicts.add(detail("CONSIST_POSITION_DUPLICATE", d -> {
                        d.put("position", entry.getKey());
                        d.put("firstIndex", firstIndex);
                        d.put("otherIndex", otherIndex);
                    }));
                }
            }
        }
        return conflicts;
    }

    /**
     * 互斥类别间隔检查（在完整编组上）。对每条规则扫描两类车厢的全部跨类位置对，
     * 位置距离（|pA-pB|，节）小于要求时输出实际值、要求值与差额，按稳定顺序排列。
     */
    public static List<Map<String, Object>> findSeparationConflicts(List<HazardRule> rules,
                                                                    List<Car> cars) {
        List<Car> sorted = cars.stream().sorted(Comparator.comparingInt(Car::position)).toList();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (HazardRule rule : rules) {
            List<Car> classACars = sorted.stream()
                    .filter(c -> rule.classA().equals(c.hazardClass())).toList();
            List<Car> classBCars = sorted.stream()
                    .filter(c -> rule.classB().equals(c.hazardClass())).toList();
            for (Car a : classACars) {
                for (Car b : classBCars) {
                    int actual = Math.abs(a.position() - b.position());
                    if (actual < rule.minSeparationCars()) {
                        int actualCars = actual;
                        conflicts.add(detail("HAZARD_SEPARATION", d -> {
                            d.put("classA", rule.classA());
                            d.put("classB", rule.classB());
                            d.put("positionA", a.position());
                            d.put("positionB", b.position());
                            d.put("actualCars", actualCars);
                            d.put("requiredCars", rule.minSeparationCars());
                            d.put("deficitCars", rule.minSeparationCars() - actualCars);
                        }));
                    }
                }
            }
        }
        return conflicts;
    }

    /**
     * 限制区段类别授权检查（在完整区段路径与完整编组上）。
     *
     * @param pathSections                 计划完整区段路径（去重），仅决定检查顺序
     * @param cars                         完整编组
     * @param allowedByConfiguredSection   已配置（受限）区段的开放清单；未在此 map 中的区段不限制
     */
    public static List<Map<String, Object>> findUnauthorizedClasses(
            List<String> pathSections, List<Car> cars,
            Map<String, List<String>> allowedByConfiguredSection) {
        List<Car> hazardCars = cars.stream()
                .filter(c -> c.hazardClass() != null)
                .sorted(Comparator.comparingInt(Car::position))
                .toList();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String sectionId : pathSections.stream().sorted().toList()) {
            List<String> allowed = allowedByConfiguredSection.get(sectionId);
            if (allowed == null) {
                continue;
            }
            for (Car car : hazardCars) {
                if (!allowed.contains(car.hazardClass())) {
                    conflicts.add(detail("HAZARD_UNAUTHORIZED", d -> {
                        d.put("sectionId", sectionId);
                        d.put("position", car.position());
                        d.put("hazardClass", car.hazardClass());
                        d.put("allowedClasses", allowed);
                    }));
                }
            }
        }
        return conflicts;
    }

    private static Map<String, Object> detail(String type,
                                              java.util.function.Consumer<Map<String, Object>> filler) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        filler.accept(map);
        return map;
    }
}
