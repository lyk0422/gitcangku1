package com.example.starter.race.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 组别重分批次的纯校验逻辑，不涉及数据库与时间。
 */
public final class ReclassRules {

    private ReclassRules() {
    }

    /**
     * 找出批次中重复出现的参赛号；无重复返回 null。
     *
     * @param bibs 批次内各申请对应的参赛号（按申请顺序）
     * @return 第一个重复的参赛号；无重复为 null
     */
    public static String findDuplicateBib(List<String> bibs) {
        Set<String> seen = new HashSet<>();
        for (String bib : bibs) {
            if (!seen.add(bib)) {
                return bib;
            }
        }
        return null;
    }

    /**
     * 计算批次迁移后某组别的在赛人数：当前在赛人数 - 迁出人数 + 迁入人数。
     */
    public static int projectedOccupancy(int currentOccupancy, int outgoing, int incoming) {
        return currentOccupancy - outgoing + incoming;
    }

    /**
     * 判断迁移后在赛人数是否超出组别容量。
     */
    public static boolean exceedsCapacity(int currentOccupancy, int outgoing, int incoming, int capacity) {
        return projectedOccupancy(currentOccupancy, outgoing, incoming) > capacity;
    }
}