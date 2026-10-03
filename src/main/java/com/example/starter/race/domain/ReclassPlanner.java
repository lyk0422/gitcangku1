package com.example.starter.race.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 组别重分的纯逻辑规划：按“迁出减一、迁入加一”的净迁移计算各组别迁移后在赛人数，
 * 校验目标组别容量是否被突破；不涉及数据库与时间。
 */
public final class ReclassPlanner {

    private ReclassPlanner() {
    }

    /**
     * 单名选手的组别迁移。
     *
     * @param fromCategoryCode 迁移前所属组别代码；未分组为 null（不释放任何组别名额）
     * @param toCategoryCode   迁移后所属组别代码
     */
    public record Migration(String fromCategoryCode, String toCategoryCode) {
    }

    /**
     * 计算整批迁移后的各组别人数，返回第一个超出容量的组别代码；全部满足容量返回 null。
     *
     * @param activeCounts 各组别当前在赛（未退赛）人数
     * @param capacities   各组别容量上限
     * @param migrations   本批迁移清单（同批次内迁出即释放名额，可同批 A→B 与 B→A）
     */
    public static String firstCapacityViolation(
            Map<String, Integer> activeCounts,
            Map<String, Integer> capacities,
            List<Migration> migrations) {
        Map<String, Integer> finalCounts = new TreeMap<>(activeCounts);
        for (Migration migration : migrations) {
            if (migration.fromCategoryCode() != null) {
                finalCounts.merge(migration.fromCategoryCode(), -1, Integer::sum);
            }
            finalCounts.merge(migration.toCategoryCode(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : finalCounts.entrySet()) {
            int capacity = capacities.getOrDefault(entry.getKey(), Integer.MAX_VALUE);
            if (entry.getValue() > capacity) {
                return entry.getKey();
            }
        }
        return null;
    }
}