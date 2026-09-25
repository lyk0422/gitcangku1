package com.example.starter.translation.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语种回退链纯逻辑：由“语种 → 回退语种”映射构成有向链，
 * 提供沿链展开（含防御性断环）与全图无环校验。不依赖 Spring，可独立单元测试。
 */
public final class FallbackChain {

    private FallbackChain() {
    }

    /**
     * 从指定语种出发沿回退链展开，返回含自身在内的有序语种列表（首个为直接语种）。
     * 遇到未配置的语种即终止；即使数据异常成环也不会死循环（重复语种截断）。
     */
    public static List<String> chain(String language, Map<String, String> fallbacks) {
        List<String> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String current = language;
        while (current != null && visited.add(current)) {
            chain.add(current);
            current = fallbacks.get(current);
        }
        return chain;
    }

    /**
     * 校验整张回退图无环：任一语种出发沿链回到已访问语种即视为成环。
     * 配置修改后须在同事务内对文档全部目标语种复核，成环则整次回滚。
     */
    public static boolean hasCycle(Map<String, String> fallbacks) {
        for (String start : fallbacks.keySet()) {
            Set<String> visited = new HashSet<>();
            String current = start;
            while (current != null) {
                if (!visited.add(current)) {
                    return true;
                }
                current = fallbacks.get(current);
            }
        }
        return false;
    }
}
