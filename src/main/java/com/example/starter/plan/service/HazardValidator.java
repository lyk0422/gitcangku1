package com.example.starter.plan.service;

import com.example.starter.plan.model.HazardCar;
import com.example.starter.plan.model.HazardConfigVersion;
import com.example.starter.plan.model.Occupancy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 危险品隔离校验引擎（无外部依赖的纯逻辑，便于单元测试）。
 *
 * <p>在完整编组与完整区段路径上执行四类裁决：
 * <ol>
 *   <li>限制区段配置无效：已登记的限制区段开放清单为空；</li>
 *   <li>车厢位置重复 / 危险品车厢所属列车不在计划路径中；</li>
 *   <li>同一列车内互斥类别车厢间隔不足（实际相隔 = 位置差 - 1）；</li>
 *   <li>列车经过限制区段时，车上危险品类别不在该区段开放清单内。</li>
 * </ol>
 * 冲突明细按类型与业务键稳定排序，数量类字段同时给出实际值、要求值与差额。
 */
@Component
public class HazardValidator {

    /** 配置无效：限制区段开放清单为空。 */
    public static final String TYPE_CONFIG_INVALID = "HAZARD_CONFIG_INVALID";
    /** 危险品车厢所属列车不在计划占用（完整路径）中。 */
    public static final String TYPE_TRAIN_NOT_IN_PATH = "HAZARD_TRAIN_NOT_IN_PATH";
    /** 同一列车编组位置重复。 */
    public static final String TYPE_DUPLICATE_POSITION = "HAZARD_DUPLICATE_POSITION";
    /** 互斥类别车厢间隔不足。 */
    public static final String TYPE_SEPARATION_INSUFFICIENT = "HAZARD_SEPARATION_INSUFFICIENT";
    /** 危险品类别未经限制区段开放清单授权。 */
    public static final String TYPE_CLASS_UNAUTHORIZED = "HAZARD_CLASS_UNAUTHORIZED";

    /**
     * 发布时刻生效的配置快照。
     *
     * @param version           配置版本主记录；无生效配置时为 null（此时无任何限制）
     * @param separations       互斥类别间隔规则
     * @param restrictedSections 全部已登记限制区段（含开放清单为空的无效区段）
     * @param openBySection     区段 -> 开放类别集合
     */
    public record EffectiveConfig(HazardConfigVersion version,
                                  Map<String, Map<String, Integer>> separations,
                                  SortedSet<String> restrictedSections,
                                  TreeMap<String, SortedSet<String>> openBySection) {

        /**
         * @return 类别对（字典序归一化）的最小相隔车厢数；无规则返回 null
         */
        public Integer minCarsBetween(String classA, String classB) {
            String a = classA;
            String b = classB;
            if (a.compareTo(b) > 0) {
                String t = a;
                a = b;
                b = t;
            }
            Map<String, Integer> rules = separations.get(a);
            return rules == null ? null : rules.get(b);
        }
    }

    /**
     * 完整编组与完整区段路径校验，返回全部冲突明细（空列表表示通过）。
     *
     * @param occupancies 计划全部区段占用（完整路径）
     * @param cars        计划全部危险品车厢（完整编组）
     * @param config      发布时刻生效配置；为 null 表示无生效配置
     */
    public List<Map<String, Object>> validate(List<Occupancy> occupancies, List<HazardCar> cars,
                                              EffectiveConfig config) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        if (config != null) {
            conflicts.addAll(findInvalidRestrictedSections(config));
        }
        conflicts.addAll(findFormationConflicts(occupancies, cars));
        conflicts.addAll(findSeparationConflicts(cars, config));
        conflicts.addAll(findUnauthorizedClasses(occupancies, cars, config));
        return conflicts;
    }

    /**
     * 仅编组完整性校验（登记草稿/整体替换时调用）：位置重复、列车不在路径中。
     */
    public List<Map<String, Object>> validateFormation(List<Occupancy> occupancies,
                                                       List<HazardCar> cars) {
        return findFormationConflicts(occupancies, cars);
    }

    // ---------- 配置有效性 ----------

    private List<Map<String, Object>> findInvalidRestrictedSections(EffectiveConfig config) {
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String sectionId : config.restrictedSections()) {
            SortedSet<String> open = config.openBySection().getOrDefault(sectionId, new TreeSet<>());
            if (open.isEmpty()) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", TYPE_CONFIG_INVALID);
                detail.put("sectionId", sectionId);
                detail.put("openHazardClasses", List.of());
                conflicts.add(detail);
            }
        }
        return conflicts;
    }

    // ---------- 编组完整性 ----------

    private List<Map<String, Object>> findFormationConflicts(List<Occupancy> occupancies,
                                                             List<HazardCar> cars) {
        SortedSet<String> trainsInPath = new TreeSet<>();
        for (Occupancy o : occupancies) {
            trainsInPath.add(o.trainNo());
        }
        Map<String, List<HazardCar>> byTrain = groupByTrain(cars);
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<HazardCar>> entry : byTrain.entrySet()) {
            String trainNo = entry.getKey();
            List<HazardCar> trainCars = entry.getValue();
            if (!trainsInPath.contains(trainNo)) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", TYPE_TRAIN_NOT_IN_PATH);
                detail.put("trainNo", trainNo);
                detail.put("positions", trainCars.stream().map(HazardCar::position)
                        .sorted().toList());
                conflicts.add(detail);
            }
            // 位置 -> 该位置上的车厢（稳定按类别排序），位置重复时成组列出冲突对
            TreeMap<Integer, List<HazardCar>> byPosition = new TreeMap<>();
            for (HazardCar car : trainCars) {
                byPosition.computeIfAbsent(car.position(), k -> new ArrayList<>()).add(car);
            }
            byPosition.forEach((position, samePosition) -> {
                if (samePosition.size() > 1) {
                    List<String> classes = samePosition.stream().map(HazardCar::hazardClass)
                            .sorted().toList();
                    for (int i = 0; i < classes.size(); i++) {
                        for (int j = i + 1; j < classes.size(); j++) {
                            Map<String, Object> detail = new LinkedHashMap<>();
                            detail.put("type", TYPE_DUPLICATE_POSITION);
                            detail.put("trainNo", trainNo);
                            detail.put("position", position);
                            detail.put("hazardClassA", classes.get(i));
                            detail.put("hazardClassB", classes.get(j));
                            conflicts.add(detail);
                        }
                    }
                }
            });
        }
        return conflicts;
    }

    // ---------- 互斥类别间隔 ----------

    private List<Map<String, Object>> findSeparationConflicts(List<HazardCar> cars,
                                                               EffectiveConfig config) {
        if (config == null) {
            return List.of();
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (List<HazardCar> trainCars : groupByTrain(cars).values()) {
            List<HazardCar> sorted = trainCars.stream()
                    .sorted(Comparator.comparingInt(HazardCar::position)
                            .thenComparing(HazardCar::hazardClass)
                            .thenComparingLong(HazardCar::id))
                    .toList();
            for (int i = 0; i < sorted.size(); i++) {
                for (int j = i + 1; j < sorted.size(); j++) {
                    HazardCar a = sorted.get(i);
                    HazardCar b = sorted.get(j);
                    Integer required = config.minCarsBetween(a.hazardClass(), b.hazardClass());
                    if (required == null || a.position() == b.position()) {
                        continue;
                    }
                    int actual = Math.abs(b.position() - a.position()) - 1;
                    if (actual < required) {
                        String classA = a.hazardClass();
                        String classB = b.hazardClass();
                        if (classA.compareTo(classB) > 0) {
                            String t = classA;
                            classA = classB;
                            classB = t;
                        }
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("type", TYPE_SEPARATION_INSUFFICIENT);
                        detail.put("trainNo", a.trainNo());
                        detail.put("hazardClassA", classA);
                        detail.put("hazardClassB", classB);
                        detail.put("positionA", Math.min(a.position(), b.position()));
                        detail.put("positionB", Math.max(a.position(), b.position()));
                        detail.put("actualCars", actual);
                        detail.put("requiredCars", required);
                        detail.put("shortfallCars", required - actual);
                        conflicts.add(detail);
                    }
                }
            }
        }
        return conflicts;
    }

    // ---------- 限制区段授权 ----------

    private List<Map<String, Object>> findUnauthorizedClasses(List<Occupancy> occupancies,
                                                               List<HazardCar> cars,
                                                               EffectiveConfig config) {
        if (config == null || config.restrictedSections().isEmpty()) {
            return List.of();
        }
        // 列车 -> 完整区段路径
        TreeMap<String, SortedSet<String>> pathByTrain = new TreeMap<>();
        for (Occupancy o : occupancies) {
            if (config.restrictedSections().contains(o.sectionId())) {
                pathByTrain.computeIfAbsent(o.trainNo(), k -> new TreeSet<>()).add(o.sectionId());
            }
        }
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (HazardCar car : cars.stream()
                .sorted(Comparator.comparing(HazardCar::trainNo)
                        .thenComparingInt(HazardCar::position)
                        .thenComparing(HazardCar::hazardClass))
                .toList()) {
            SortedSet<String> path = pathByTrain.get(car.trainNo());
            if (path == null) {
                continue;
            }
            for (String sectionId : path) {
                SortedSet<String> open = config.openBySection()
                        .getOrDefault(sectionId, new TreeSet<>());
                if (!open.contains(car.hazardClass())) {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("type", TYPE_CLASS_UNAUTHORIZED);
                    detail.put("sectionId", sectionId);
                    detail.put("trainNo", car.trainNo());
                    detail.put("position", car.position());
                    detail.put("hazardClass", car.hazardClass());
                    detail.put("openHazardClasses", List.copyOf(open));
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    private Map<String, List<HazardCar>> groupByTrain(List<HazardCar> cars) {
        Map<String, List<HazardCar>> byTrain = new TreeMap<>();
        for (HazardCar car : cars) {
            byTrain.computeIfAbsent(car.trainNo(), k -> new ArrayList<>()).add(car);
        }
        return byTrain;
    }
}
