package com.example.starter.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 锁定图依赖命中路径计算：在已锁定的精确版本闭包上自根制品做确定性广度优先遍历。
 *
 * <p>邻接按依赖名称字典序展开，保证同一锁定图任意时刻得到稳定路径；
 * 路径渲染形如 {@code app:1>lib:2>util:1}。
 */
public final class NoticeHitPaths {

    private NoticeHitPaths() {
    }

    /**
     * 计算闭包内每个制品自根制品的命中路径。
     *
     * @param rootName            根制品名称（必须存在于 entries）
     * @param entries             名称 -> 锁定精确版本（锁定图的最终依赖闭包）
     * @param dependenciesByName  名称 -> 该制品锁定版本声明的依赖区间
     * @return 名称 -> 命中路径字符串；根制品路径为其自身坐标
     */
    public static Map<String, String> compute(String rootName,
                                              Map<String, Integer> entries,
                                              Map<String, List<DependencyRange>> dependenciesByName) {
        Map<String, String> paths = new HashMap<>();
        Integer rootVersion = entries.get(rootName);
        if (rootVersion == null) {
            return paths;
        }
        paths.put(rootName, rootName + ":" + rootVersion);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootName);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<DependencyRange> deps = new ArrayList<>(
                    dependenciesByName.getOrDefault(current, List.of()));
            deps.sort((a, b) -> a.name().compareTo(b.name()));
            for (DependencyRange dep : deps) {
                Integer targetVersion = entries.get(dep.name());
                if (targetVersion == null || paths.containsKey(dep.name())) {
                    continue;
                }
                if (targetVersion < dep.minimumVersion() || targetVersion > dep.maximumVersion()) {
                    continue;
                }
                paths.put(dep.name(), paths.get(current) + ">" + dep.name() + ":" + targetVersion);
                queue.add(dep.name());
            }
        }
        return new TreeMap<>(paths);
    }
}
