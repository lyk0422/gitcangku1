package com.example.starter.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 来源策略校验器：在锁定解析结果上逐坐标校验来源证明，并给出根到坐标的完整路径。
 *
 * <p>校验规则（按提交顺序裁决后的当前有效证明）：
 * <ul>
 *   <li>每个解析坐标必须存在未撤销证明：无证明 {@code MISSING_ATTESTATION}，
 *       最新证明已撤销 {@code ATTESTATION_REVOKED}；</li>
 *   <li>策略列出该坐标时：构建摘要必须一致 {@code DIGEST_MISMATCH}，
 *       证明等级不得低于要求 {@code LEVEL_INSUFFICIENT}；</li>
 *   <li>策略未列出的坐标仍须持有有效证明（证明本身带来源仓、摘要与等级）。</li>
 * </ul>
 */
public final class ProvenancePolicyEvaluator {

    private ProvenancePolicyEvaluator() {
    }

    /** 坐标证明查询结果。 */
    public record LookupResult(State state, ProvenanceAttestation attestation) {

        /** VALID=存在未撤销证明；REVOKED=最新证明已撤销；ABSENT=从未证明。 */
        public enum State {VALID, REVOKED, ABSENT}
    }

    /** 证明查询接口，由服务层在事务快照内实现。 */
    @FunctionalInterface
    public interface AttestationLookup {
        LookupResult find(String name, int version);
    }

    /** 单坐标来源检查结果。 */
    public record CoordinateProvenance(
            String name,
            int version,
            List<String> path,
            boolean matched,
            String reason,
            ProvenanceAttestation attestation) {
    }

    /**
     * 逐坐标检查来源合规性，坐标按名称升序返回。
     *
     * @param chosen   解析结果：名称（升序）-> 选中的制品版本（含依赖声明）
     * @param rootName 根坐标名称
     * @param policy   当前命中的策略版本（非空）
     * @param lookup   证明查询
     */
    public static List<CoordinateProvenance> inspect(
            Map<String, ArtifactVersion> chosen,
            String rootName,
            ProvenancePolicy policy,
            AttestationLookup lookup) {
        Map<String, PolicyCoordinate> requirements = policy.coordinates().stream()
                .collect(Collectors.toMap(PolicyCoordinate::name, Function.identity(),
                        (a, b) -> a, TreeMap::new));
        Map<String, List<String>> paths = dependencyPaths(chosen, rootName);

        List<CoordinateProvenance> results = new ArrayList<>();
        for (ArtifactVersion artifact : chosen.values()) {
            String name = artifact.name();
            int version = artifact.version();
            List<String> path = List.copyOf(paths.getOrDefault(name, List.of(name + ":" + version)));
            LookupResult found = lookup.find(name, version);
            String reason = null;
            boolean matched = false;

            if (found.state() == LookupResult.State.ABSENT) {
                reason = ProvenanceViolation.MISSING_ATTESTATION;
            } else if (found.state() == LookupResult.State.REVOKED) {
                reason = ProvenanceViolation.ATTESTATION_REVOKED;
            } else {
                ProvenanceAttestation attestation = found.attestation();
                PolicyCoordinate required = requirements.get(name);
                if (required != null && !required.requiredDigest().isBlank()
                        && !required.requiredDigest().equals(attestation.buildDigest())) {
                    reason = ProvenanceViolation.DIGEST_MISMATCH;
                } else if (required != null && attestation.attestationLevel() < required.requiredLevel()) {
                    reason = ProvenanceViolation.LEVEL_INSUFFICIENT;
                } else {
                    matched = true;
                }
            }
            results.add(new CoordinateProvenance(name, version, path, matched, reason,
                    found.state() == LookupResult.State.VALID ? found.attestation() : null));
        }
        return List.copyOf(results);
    }

    /** 执行合规校验，返回全部违规项（含完整路径与可区分原因）。 */
    public static List<ProvenanceViolation> evaluate(
            Map<String, ArtifactVersion> chosen,
            String rootName,
            ProvenancePolicy policy,
            AttestationLookup lookup) {
        return violations(inspect(chosen, rootName, policy, lookup), policy);
    }

    /** 依据逐坐标检查结果构造全部违规项，避免对同一批坐标重复查询证明。 */
    public static List<ProvenanceViolation> violations(
            List<CoordinateProvenance> inspected, ProvenancePolicy policy) {
        Map<String, PolicyCoordinate> requirements = policy.coordinates().stream()
                .collect(Collectors.toMap(PolicyCoordinate::name, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));
        List<ProvenanceViolation> result = new ArrayList<>();
        for (CoordinateProvenance cp : inspected) {
            if (cp.matched()) {
                continue;
            }
            String detail = switch (cp.reason()) {
                case ProvenanceViolation.MISSING_ATTESTATION ->
                        "制品 " + cp.name() + ":" + cp.version() + " 缺少来源证明";
                case ProvenanceViolation.ATTESTATION_REVOKED ->
                        "制品 " + cp.name() + ":" + cp.version() + " 的最新来源证明已撤销";
                case ProvenanceViolation.DIGEST_MISMATCH -> {
                    String expected = requirements.get(cp.name()).requiredDigest();
                    String actual = cp.attestation() == null ? "" : cp.attestation().buildDigest();
                    yield "制品 " + cp.name() + ":" + cp.version()
                            + " 构建摘要不匹配：expected=" + expected + ", actual=" + actual;
                }
                case ProvenanceViolation.LEVEL_INSUFFICIENT -> {
                    int required = requirements.get(cp.name()).requiredLevel();
                    Integer actual = cp.attestation() == null ? null : cp.attestation().attestationLevel();
                    yield "制品 " + cp.name() + ":" + cp.version()
                            + " 证明等级不足：required>=" + required + ", actual=" + actual;
                }
                default -> "制品 " + cp.name() + ":" + cp.version() + " 来源不合规";
            };
            result.add(new ProvenanceViolation(cp.reason(), cp.path(), detail));
        }
        return List.copyOf(result);
    }

    /**
     * 在解析图上自根坐标做 BFS，求每个坐标一条根 -> 坐标的完整路径（坐标格式 name:version）。
     * 图中允许环：首次访问确定父节点。
     */
    private static Map<String, List<String>> dependencyPaths(
            Map<String, ArtifactVersion> chosen, String rootName) {
        Map<String, String> parent = new LinkedHashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        parent.put(rootName, null);
        queue.add(rootName);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            ArtifactVersion artifact = chosen.get(current);
            if (artifact == null) {
                continue;
            }
            // 依赖按名称排序遍历，保证路径确定性。
            for (String depName : artifact.dependencies().stream()
                    .map(DependencyRange::name).distinct().sorted().toList()) {
                if (chosen.containsKey(depName) && !parent.containsKey(depName)) {
                    parent.put(depName, current);
                    queue.add(depName);
                }
            }
        }

        Map<String, List<String>> paths = new TreeMap<>();
        for (String name : chosen.keySet()) {
            if (!parent.containsKey(name)) {
                // 理论上不可达：解析闭包内所有节点均自根可达；防御性保留单点路径。
                paths.put(name, List.of(coordinate(name, chosen.get(name))));
                continue;
            }
            List<String> reversed = new ArrayList<>();
            for (String node = name; node != null; node = parent.get(node)) {
                reversed.add(coordinate(node, chosen.get(node)));
            }
            Collections.reverse(reversed);
            paths.put(name, List.copyOf(reversed));
        }
        return paths;
    }

    private static String coordinate(String name, ArtifactVersion artifact) {
        return name + ":" + (artifact == null ? "?" : artifact.version());
    }
}
