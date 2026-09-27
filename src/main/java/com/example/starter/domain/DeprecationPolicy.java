package com.example.starter.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 弃用窗口在锁定时刻的裁决策略（纯逻辑）。
 *
 * <p>时间口径全部使用 UTC、区间左闭右开：解析时刻 {@code now &lt; effectiveAt} 时
 * 依赖闭包命中弃用版本仅产生 {@code DEPRECATION_WARNING}；{@code now &gt;= effectiveAt}
 * 时整次锁定失败（422）。告警与违规均按制品名称、版本稳定排序。
 */
public final class DeprecationPolicy {

    private DeprecationPolicy() {
    }

    /**
     * 裁决结果：生效前窗口为告警（锁定可继续），生效及以后窗口为违规（整次 422）。
     *
     * @param warnings   命中但尚未生效的弃用窗口，按名称、版本升序
     * @param violations 已生效的弃用窗口，按名称、版本升序
     */
    public record Adjudication(
            List<DeprecationWindow> warnings,
            List<DeprecationWindow> violations) {
    }

    /**
     * 在一致性快照上对解析闭包裁决。
     *
     * @param snapshot 含有效（未撤销）弃用窗口的仓库快照
     * @param closure  解析出的名称 -&gt; 精确版本闭包
     * @param now      解析时刻（UTC，由可注入时钟给出）
     */
    public static Adjudication adjudicate(RepositorySnapshot snapshot,
                                          Map<String, Integer> closure,
                                          Instant now) {
        TreeMap<String, DeprecationWindow> warnings = new TreeMap<>();
        TreeMap<String, DeprecationWindow> violations = new TreeMap<>();
        closure.forEach((name, version) -> {
            Map<Integer, DeprecationWindow> perName = snapshot.deprecations().get(name);
            if (perName == null) {
                return;
            }
            DeprecationWindow window = perName.get(version);
            if (window == null) {
                return;
            }
            // 左闭右开：now < effectiveAt 为告警区间；now >= effectiveAt 为生效。
            if (now.isBefore(window.effectiveAt())) {
                warnings.put(name, window);
            } else {
                violations.put(name, window);
            }
        });
        return new Adjudication(List.copyOf(warnings.values()), List.copyOf(violations.values()));
    }

    /**
     * 校验同一制品内“弃用版本 -&gt; 替代版本”有向图是否存在环。
     *
     * @param edges 版本号 -&gt; 替代版本号（已合并存量窗口与本次批量登记）
     * @return 成环返回 true
     */
    public static boolean graphHasCycle(Map<Integer, Integer> edges) {
        // 0=未访问，1=在当前路径上，2=已完成
        java.util.Map<Integer, Integer> color = new java.util.HashMap<>();
        for (Integer node : edges.keySet()) {
            if (color.getOrDefault(node, 0) == 0 && visitsCycle(node, edges, color)) {
                return true;
            }
        }
        return false;
    }

    private static boolean visitsCycle(Integer node, Map<Integer, Integer> edges,
                                       java.util.Map<Integer, Integer> color) {
        color.put(node, 1);
        Integer next = edges.get(node);
        if (next != null) {
            int nextColor = color.getOrDefault(next, 0);
            if (nextColor == 1) {
                return true;
            }
            if (nextColor == 0 && visitsCycle(next, edges, color)) {
                return true;
            }
        }
        color.put(node, 2);
        return false;
    }
}
