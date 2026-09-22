package com.example.starter.resolver;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 依赖区间回溯求解器：根版本固定，其余名称按字典序选取，候选版本从高到低尝试，
 * 任一已选制品之间的闭区间约束冲突即回溯；依赖成环时环上约束同样参与校验，
 * 不会因成环死循环或误判；取首个完整可行结果而非贪心最高版本。
 */
public final class DependencyResolver {

    private DependencyResolver() {
    }

    /**
     * 一个制品版本的不可变快照。
     */
    public record ArtifactVersion(String name, int version,
                                  List<DependencyRange> dependencies) {
    }

    /**
     * 闭区间依赖声明。
     */
    public record DependencyRange(String name, int minVersion, int maxVersion) {

        public boolean contains(int version) {
            return version >= minVersion && version <= maxVersion;
        }
    }

    /**
     * 回溯求解精确版本集合。
     *
     * @param rootName    根名称（版本固定）
     * @param rootVersion 根精确版本
     * @param artifacts   仓库内所有未撤回制品版本快照
     * @return 名称到精确版本的有序映射（按名称字典序）；无可行组合时为空
     */
    public static Optional<Map<String, Integer>> resolve(
            String rootName, int rootVersion, List<ArtifactVersion> artifacts) {

        // 名称 -> 版本（降序）-> 制品版本快照
        Map<String, TreeMap<Integer, ArtifactVersion>> byName = new HashMap<>();
        for (ArtifactVersion artifact : artifacts) {
            byName.computeIfAbsent(artifact.name(),
                            k -> new TreeMap<>(Comparator.reverseOrder()))
                    .put(artifact.version(), artifact);
        }

        ArtifactVersion root = byName.getOrDefault(rootName, new TreeMap<>()).get(rootVersion);
        if (root == null) {
            return Optional.empty();
        }

        // 已选名称 -> 精确版本；TreeMap 保证按字典序选取下一个待解析名称
        TreeMap<String, Integer> chosen = new TreeMap<>();
        chosen.put(rootName, rootVersion);

        // 校验根自身的依赖区间（含根对自身的闭区间约束）
        if (!consistent(rootName, root, chosen, byName)) {
            return Optional.empty();
        }

        if (!backtrack(chosen, rootName, byName)) {
            return Optional.empty();
        }
        return Optional.of(new LinkedHashMap<>(chosen));
    }

    /**
     * 深度优先回溯。
     *
     * @param chosen    当前已选名称及精确版本
     * @param fixedRoot 根名称，其版本永不回退
     * @param byName    全仓库未撤回版本索引
     */
    private static boolean backtrack(TreeMap<String, Integer> chosen, String fixedRoot,
                                     Map<String, TreeMap<Integer, ArtifactVersion>> byName) {
        String next = nextPendingName(chosen, byName);
        if (next == null) {
            // 所有已选制品的依赖名称均已解析且区间已在试选时校验，得到完整可行解
            return true;
        }

        TreeMap<Integer, ArtifactVersion> candidates = byName.get(next);
        if (candidates == null) {
            // 被依赖名称在仓库中没有任何未撤回版本
            return false;
        }

        for (Map.Entry<Integer, ArtifactVersion> entry : candidates.entrySet()) {
            int candidateVersion = entry.getKey();
            ArtifactVersion candidate = entry.getValue();
            if (!consistent(next, candidate, chosen, byName)) {
                continue;
            }
            chosen.put(next, candidateVersion);
            if (backtrack(chosen, fixedRoot, byName)) {
                return true;
            }
            chosen.remove(next);
        }
        return false;
    }

    /**
     * 计算已选制品依赖中、尚未解析名称的字典序最小者；全部已解析返回 null。
     */
    private static String nextPendingName(
            TreeMap<String, Integer> chosen,
            Map<String, TreeMap<Integer, ArtifactVersion>> byName) {
        String result = null;
        for (Map.Entry<String, Integer> entry : chosen.entrySet()) {
            ArtifactVersion selected = byName.get(entry.getKey()).get(entry.getValue());
            if (selected == null) {
                continue;
            }
            for (DependencyRange range : selected.dependencies()) {
                if (!chosen.containsKey(range.name())
                        && (result == null || range.name().compareTo(result) < 0)) {
                    result = range.name();
                }
            }
        }
        return result;
    }

    /**
     * 试选 candidate 后，双向校验它与所有已选制品之间的闭区间约束（含成环回边）。
     */
    private static boolean consistent(String candidateName, ArtifactVersion candidate,
                                      TreeMap<String, Integer> chosen,
                                      Map<String, TreeMap<Integer, ArtifactVersion>> byName) {
        // 候选制品对已选名称的依赖必须命中已选精确版本
        for (DependencyRange range : candidate.dependencies()) {
            Integer resolved = chosen.get(range.name());
            if (resolved != null && !range.contains(resolved)) {
                return false;
            }
        }
        // 已选制品对候选名称的依赖必须容纳候选版本
        for (Map.Entry<String, Integer> entry : chosen.entrySet()) {
            ArtifactVersion selected = byName.get(entry.getKey()).get(entry.getValue());
            if (selected == null) {
                continue;
            }
            for (DependencyRange range : selected.dependencies()) {
                if (range.name().equals(candidateName) && !range.contains(candidate.version())) {
                    return false;
                }
            }
        }
        return true;
    }
}
