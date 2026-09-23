package com.example.starter.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 策略静态校验器：发布前在应用层保证集合级不变量。
 *
 * <ul>
 *   <li>候选优先级必须为从 1 开始的连续整数且不重复；</li>
 *   <li>原坐标模式与任一替代坐标不能相同；</li>
 *   <li>同一策略内、同平台的规则源模式不得重叠命中；</li>
 *   <li>替代链（模式静态展开 + 候选坐标）不得形成坐标环。</li>
 * </ul>
 */
public final class PolicyValidator {

    private PolicyValidator() {
    }

    /**
     * 校验待发布规则集合，非法时抛出 {@link SubstitutionFailureException}（调用方映射为 422）。
     *
     * <p>生效时刻允许早于发布时刻（表示立即生效或补发），时间门控只发生在锁解析侧。
     *
     * @param rules 按请求顺序排列的规则
     */
    public static void validate(List<SubstitutionRule> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new SubstitutionFailureException("策略至少包含一条规则");
        }
        for (int i = 0; i < rules.size(); i++) {
            SubstitutionRule rule = rules.get(i);
            validateRule(rule, i);
        }
        validateOverlap(rules);
        validateNoCycle(rules);
    }

    private static void validateRule(SubstitutionRule rule, int index) {
        List<SubstitutionCandidate> ordered = rule.candidates().stream()
                .sorted(Comparator.comparingInt(SubstitutionCandidate::priority))
                .toList();
        Set<Integer> priorities = new HashSet<>();
        Set<String> coordinates = new HashSet<>();
        for (int i = 0; i < ordered.size(); i++) {
            SubstitutionCandidate candidate = ordered.get(i);
            if (candidate.priority() != i + 1) {
                throw new SubstitutionFailureException(
                        "第 " + (index + 1) + " 条规则的候选优先级必须为从 1 开始的连续整数");
            }
            if (!priorities.add(candidate.priority())) {
                throw new SubstitutionFailureException(
                        "第 " + (index + 1) + " 条规则存在重复优先级 " + candidate.priority());
            }
            if (!coordinates.add(candidate.coordinate())) {
                throw new SubstitutionFailureException(
                        "第 " + (index + 1) + " 条规则存在重复候选坐标 " + candidate.coordinate());
            }
            if (!rule.source().wildcard() && rule.source().prefix().equals(candidate.coordinate())) {
                throw new SubstitutionFailureException(
                        "第 " + (index + 1) + " 条规则的原坐标与替代坐标不能相同: "
                                + candidate.coordinate());
            }
        }
    }

    /** 同平台规则的源模式两两不得重叠（不同平台允许相同源）。 */
    private static void validateOverlap(List<SubstitutionRule> rules) {
        for (int i = 0; i < rules.size(); i++) {
            for (int j = i + 1; j < rules.size(); j++) {
                SubstitutionRule a = rules.get(i);
                SubstitutionRule b = rules.get(j);
                if (a.platform().equals(b.platform())
                        && CoordinatePattern.overlaps(a.source(), b.source())) {
                    throw new SubstitutionFailureException(
                            "同一策略内规则不得重叠命中: " + a.source().raw()
                                    + " 与 " + b.source().raw());
                }
            }
        }
    }

    /**
     * 静态展开替代链检测坐标环：按平台分组（解析时每次只沿一个平台的规则展开），
     * 以组内出现的全部候选坐标与源字面量为起点，沿“坐标命中规则 → 规则候选”的边
     * 做 DFS，遇回边即存在环。通配源按真实匹配关系展开，覆盖通配规则参与的环。
     */
    private static void validateNoCycle(List<SubstitutionRule> rules) {
        java.util.Map<String, List<SubstitutionRule>> byPlatform = new java.util.LinkedHashMap<>();
        for (SubstitutionRule rule : rules) {
            byPlatform.computeIfAbsent(rule.platform(), k -> new ArrayList<>()).add(rule);
        }
        for (Map.Entry<String, List<SubstitutionRule>> entry : byPlatform.entrySet()) {
            List<SubstitutionRule> platformRules = entry.getValue();
            java.util.Set<String> nodes = new java.util.LinkedHashSet<>();
            for (SubstitutionRule rule : platformRules) {
                if (!rule.source().wildcard()) {
                    nodes.add(rule.source().prefix());
                }
                for (SubstitutionCandidate candidate : rule.candidates()) {
                    nodes.add(candidate.coordinate());
                }
            }
            for (String start : nodes) {
                java.util.Deque<String> stack = new java.util.ArrayDeque<>();
                java.util.Set<String> onStack = new java.util.HashSet<>();
                java.util.Set<String> finished = new java.util.HashSet<>();
                if (hasCycleBack(platformRules, start, stack, onStack, finished)) {
                    throw new SubstitutionFailureException(
                            "平台 " + entry.getKey() + " 的替代链展开后形成坐标环，起点坐标: " + start);
                }
            }
        }
    }

    /**
     * 从 coordinate 出发 DFS：沿命中规则的候选边继续，命中当前栈上节点即为环。
     */
    private static boolean hasCycleBack(List<SubstitutionRule> rules, String coordinate,
                                        java.util.Deque<String> stack,
                                        java.util.Set<String> onStack,
                                        java.util.Set<String> finished) {
        if (finished.contains(coordinate)) {
            return false;
        }
        stack.push(coordinate);
        onStack.add(coordinate);
        try {
            for (SubstitutionRule rule : rules) {
                if (!rule.source().matches(coordinate)) {
                    continue;
                }
                for (SubstitutionCandidate candidate : rule.candidates()) {
                    String next = candidate.coordinate();
                    if (onStack.contains(next)) {
                        return true;
                    }
                    if (hasCycleBack(rules, next, stack, onStack, finished)) {
                        return true;
                    }
                }
            }
            finished.add(coordinate);
            return false;
        } finally {
            stack.pop();
            onStack.remove(coordinate);
        }
    }
}
