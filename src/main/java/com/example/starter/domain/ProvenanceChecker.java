package com.example.starter.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 来源策略校验器：在已解析（或已锁定）的制品集合上校验当前策略版本。
 *
 * <p>对每个坐标依次检查：证明存在、未撤销、来源仓被允许、
 * 构建摘要与制品登记摘要一致、证明等级达到策略下限。
 * 每个违规都携带从根制品出发的完整依赖路径。
 */
public final class ProvenanceChecker {

    private ProvenanceChecker() {
    }

    /**
     * 校验一个精确版本集合是否满足策略。
     *
     * @param policy        当前策略版本（调用方保证非 null）
     * @param solution      名称 -> 精确版本（待校验集合，含根）
     * @param dependencies  坐标键(name:version) -> 该坐标声明的依赖名称列表（仅用于路径还原）
     * @param attestations  坐标键 -> 当前证明（证明版本最大的一条；无证明则缺省）
     * @param artifactDigests 坐标键 -> 制品登记摘要，null 值或缺省表示未登记、不参与比对
     * @param rootName      根制品名称
     * @param rootVersion   根制品版本
     * @return 违规列表（按坐标名字典序），空表示全部满足
     */
    public static List<PolicyViolation> check(ProvenancePolicy policy,
                                              Map<String, Integer> solution,
                                              Map<String, List<String>> dependencies,
                                              Map<String, Attestation> attestations,
                                              Map<String, String> artifactDigests,
                                              String rootName,
                                              int rootVersion) {
        Map<String, String> paths = buildPaths(solution, dependencies, rootName, rootVersion);
        List<PolicyViolation> violations = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(solution).entrySet()) {
            String coordinate = entry.getKey() + ":" + entry.getValue();
            String path = paths.getOrDefault(entry.getKey(), coordinate);
            Attestation attestation = attestations.get(coordinate);
            if (attestation == null) {
                violations.add(new PolicyViolation("MISSING_ATTESTATION", coordinate, path,
                        "坐标 " + coordinate + " 缺少来源证明"));
                continue;
            }
            if (attestation.revoked()) {
                violations.add(new PolicyViolation("ATTESTATION_REVOKED", coordinate, path,
                        "坐标 " + coordinate + " 的证明版本 " + attestation.attestationVersion()
                                + " 已撤销"));
                continue;
            }
            if (!policy.allowedRepos().contains(attestation.repoId())) {
                violations.add(new PolicyViolation("REPO_NOT_ALLOWED", coordinate, path,
                        "坐标 " + coordinate + " 的来源仓 " + attestation.repoId()
                                + " 不在策略版本 " + policy.version() + " 允许集合内"));
                continue;
            }
            String artifactDigest = artifactDigests.get(coordinate);
            if (artifactDigest != null && !artifactDigest.equals(attestation.digest())) {
                violations.add(new PolicyViolation("DIGEST_MISMATCH", coordinate, path,
                        "坐标 " + coordinate + " 的证明摘要与制品登记摘要不一致"));
                continue;
            }
            if (attestation.level() < policy.minLevel()) {
                violations.add(new PolicyViolation("LEVEL_INSUFFICIENT", coordinate, path,
                        "坐标 " + coordinate + " 的证明等级 " + attestation.level()
                                + " 低于策略版本 " + policy.version() + " 要求的 " + policy.minLevel()));
            }
        }
        return violations;
    }

    /**
     * 从根出发 BFS 还原每个名称的完整依赖路径（名称 -> 路径串，如 app:1&gt;lib:2）。
     * 依赖图存在多路径时取字典序最小的下一跳组合；不可达名称退化为自身坐标。
     */
    public static Map<String, String> buildPaths(Map<String, Integer> solution,
                                                 Map<String, List<String>> dependencies,
                                                 String rootName,
                                                 int rootVersion) {
        Map<String, String> paths = new HashMap<>();
        String rootPath = rootName + ":" + rootVersion;
        paths.put(rootName, rootPath);
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootName);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            String currentCoordinate = current + ":" + solution.get(current);
            List<String> deps = dependencies.getOrDefault(currentCoordinate, List.of());
            deps.stream().sorted().forEach(dep -> {
                if (solution.containsKey(dep) && !paths.containsKey(dep)) {
                    paths.put(dep, paths.get(current) + ">" + dep + ":" + solution.get(dep));
                    queue.add(dep);
                }
            });
        }
        return paths;
    }

    /**
     * 规范化证明摘要：按名称升序拼接 name:version#attestationVersion@digest，
     * 作为 provenanceKey 指纹的证明部分。
     */
    public static String normalizedAttestationDigest(Map<String, Integer> solution,
                                                     Map<String, Attestation> attestations) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(solution).entrySet()) {
            Attestation attestation = attestations.get(entry.getKey() + ":" + entry.getValue());
            if (attestation == null) {
                throw new IllegalArgumentException("坐标缺少证明，无法生成规范化摘要: " + entry.getKey());
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue())
                    .append('#').append(attestation.attestationVersion())
                    .append('@').append(attestation.digest());
        }
        return sb.toString();
    }

    /**
     * 将坐标集合的依赖声明整理为 坐标键 -> 依赖名称列表。
     */
    public static Map<String, List<String>> dependencyIndex(Map<String, Integer> solution,
                                                            Map<String, List<DependencyRange>> declared) {
        Map<String, List<String>> index = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : solution.entrySet()) {
            String coordinate = entry.getKey() + ":" + entry.getValue();
            List<DependencyRange> ranges = declared.getOrDefault(coordinate, List.of());
            index.put(coordinate, ranges.stream().map(DependencyRange::name).toList());
        }
        return index;
    }
}
