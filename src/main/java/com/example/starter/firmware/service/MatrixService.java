package com.example.starter.firmware.service;

import com.example.starter.firmware.api.MatrixRegisterRequest;
import com.example.starter.firmware.api.MatrixRegisterResponse;
import com.example.starter.firmware.api.MatrixRevisionListResponse;
import com.example.starter.firmware.api.MatrixView;
import com.example.starter.firmware.domain.FirmwareRequirement;
import com.example.starter.firmware.domain.MatrixRevision;
import com.example.starter.firmware.domain.ModuleDependency;
import com.example.starter.firmware.domain.ModuleVersions;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.MatrixRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块兼容矩阵：登记（版本校验、依赖环校验、版本递增、历史落库）与只读查询。
 * 拉取时通过 {@link #requiredClosure(String)} 计算目标固件的完整依赖集合：
 * 直接需求加上模块依赖边的传递闭包，同一模块多路径要求取最高版本。
 */
@Service
public class MatrixService {

    private final MatrixRepository matrixRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public MatrixService(MatrixRepository matrixRepository, IdempotencyService idempotency, Clock clock) {
        this.matrixRepository = matrixRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 登记矩阵条目：先锁定矩阵元信息行使并发登记串行化，再校验完整最终状态
     * （版本格式、请求内重复、合并后依赖图无环），最后在一个事务内合并写入并推进矩阵版本；
     * 任何校验失败整体回滚、不占幂等键。
     */
    public MatrixRegisterResponse register(MatrixRegisterRequest request) {
        String fingerprint = fingerprintOf(request);
        return idempotency.execute(request.requestId(), "matrix.register", fingerprint, () -> {
            matrixRepository.lockMetaForUpdate();
            List<FirmwareRequirement> requirements = validateRequirements(request);
            List<ModuleDependency> dependencies = validateDependencies(request);
            assertNoDependencyCycle(dependencies);
            for (FirmwareRequirement requirement : requirements) {
                matrixRepository.upsertRequirement(requirement);
            }
            for (ModuleDependency dependency : dependencies) {
                matrixRepository.upsertDependency(dependency);
            }
            int newVersion = matrixRepository.bumpVersion();
            MatrixRevision revision = new MatrixRevision(newVersion, requirements.size(),
                    dependencies.size(), Instant.now(clock).toString());
            matrixRepository.insertRevision(revision);
            return new MatrixRegisterResponse(revision.version(), revision.requirementCount(),
                    revision.dependencyCount(), revision.registeredAtUtc());
        }, MatrixRegisterResponse.class);
    }

    /**
     * 当前矩阵（只读，不改变状态）。
     */
    public MatrixView current() {
        return new MatrixView(currentMatrixVersion(), matrixRepository.findAllRequirements(),
                matrixRepository.findAllDependencies());
    }

    /**
     * 登记历史（只读，不改变状态）。
     */
    public MatrixRevisionListResponse revisions() {
        return new MatrixRevisionListResponse(matrixRepository.findAllRevisions());
    }

    /**
     * 当前矩阵版本，未登记过为 0。
     */
    public int currentMatrixVersion() {
        return matrixRepository.currentVersion();
    }

    /**
     * 拉取使用的一致性矩阵快照：矩阵版本与该版本下的完整依赖集合。
     *
     * @param version 矩阵版本
     * @param closure 目标固件完整依赖集合（模块名到最低要求版本）
     */
    public record MatrixSnapshot(int version, Map<String, String> closure) {
    }

    /**
     * 读取目标固件在某个一致矩阵版本下的完整依赖集合。
     * 矩阵版本只增不减，故"版本-闭包-版本"两次读取相同即为一致快照；
     * 读取跨过登记提交点时重试，多次不一致则锁定元信息行兜底，与登记按提交顺序裁决。
     */
    public MatrixSnapshot snapshotForPull(String firmwareVersion) {
        for (int attempt = 0; attempt < 3; attempt++) {
            int before = matrixRepository.currentVersion();
            Map<String, String> closure = requiredClosure(firmwareVersion);
            int after = matrixRepository.currentVersion();
            if (before == after) {
                return new MatrixSnapshot(before, closure);
            }
        }
        int locked = matrixRepository.currentVersionForUpdate();
        return new MatrixSnapshot(locked, requiredClosure(firmwareVersion));
    }

    /**
     * 目标固件的完整依赖集合：模块名到最低要求版本（多路径取最高）。
     * 依赖图在登记时已校验无环，闭包计算必然终止。
     */
    public Map<String, String> requiredClosure(String firmwareVersion) {
        Map<String, String> required = new LinkedHashMap<>();
        for (FirmwareRequirement requirement : matrixRepository.findRequirementsByFirmware(firmwareVersion)) {
            mergeMinVersion(required, requirement.module(), requirement.minVersion());
        }
        Map<String, List<ModuleDependency>> edgesByModule = new HashMap<>();
        for (ModuleDependency dependency : matrixRepository.findAllDependencies()) {
            edgesByModule.computeIfAbsent(dependency.module(), key -> new ArrayList<>()).add(dependency);
        }
        Deque<String> queue = new ArrayDeque<>(required.keySet());
        while (!queue.isEmpty()) {
            String module = queue.poll();
            for (ModuleDependency edge : edgesByModule.getOrDefault(module, List.of())) {
                String previous = required.get(edge.dependsOn());
                if (previous == null || ModuleVersions.compare(edge.minVersion(), previous) > 0) {
                    required.put(edge.dependsOn(), edge.minVersion());
                    queue.offer(edge.dependsOn());
                }
            }
        }
        return required;
    }

    private static void mergeMinVersion(Map<String, String> required, String module, String minVersion) {
        String previous = required.get(module);
        if (previous == null || ModuleVersions.compare(minVersion, previous) > 0) {
            required.put(module, minVersion);
        }
    }

    private List<FirmwareRequirement> validateRequirements(MatrixRegisterRequest request) {
        Set<String> seen = new HashSet<>();
        List<FirmwareRequirement> requirements = new ArrayList<>();
        for (MatrixRegisterRequest.RequirementEntry entry : request.firmwareRequirements()) {
            ModuleVersions.validate(entry.minVersion());
            if (!seen.add(entry.firmwareVersion() + " " + entry.module())) {
                throw ApiException.badRequest("DUPLICATE_MATRIX_ENTRY",
                        "同一请求内固件需求重复: " + entry.firmwareVersion() + "/" + entry.module());
            }
            requirements.add(new FirmwareRequirement(entry.firmwareVersion(), entry.module(),
                    entry.minVersion()));
        }
        return requirements;
    }

    private List<ModuleDependency> validateDependencies(MatrixRegisterRequest request) {
        Set<String> seen = new HashSet<>();
        List<ModuleDependency> dependencies = new ArrayList<>();
        for (MatrixRegisterRequest.DependencyEntry entry : request.moduleDependencies()) {
            ModuleVersions.validate(entry.minVersion());
            if (!seen.add(entry.module() + " " + entry.dependsOn())) {
                throw ApiException.badRequest("DUPLICATE_MATRIX_ENTRY",
                        "同一请求内模块依赖重复: " + entry.module() + "->" + entry.dependsOn());
            }
            dependencies.add(new ModuleDependency(entry.module(), entry.dependsOn(),
                    entry.minVersion()));
        }
        return dependencies;
    }

    /**
     * 校验合并后的模块依赖图无环：既有边与本次登记边一起参与，成环则整体拒绝。
     */
    private void assertNoDependencyCycle(List<ModuleDependency> incoming) {
        Map<String, List<String>> graph = new HashMap<>();
        for (ModuleDependency edge : matrixRepository.findAllDependencies()) {
            graph.computeIfAbsent(edge.module(), key -> new ArrayList<>()).add(edge.dependsOn());
        }
        for (ModuleDependency edge : incoming) {
            List<String> targets = graph.computeIfAbsent(edge.module(), key -> new ArrayList<>());
            if (!targets.contains(edge.dependsOn())) {
                targets.add(edge.dependsOn());
            }
        }
        Map<String, Integer> state = new HashMap<>();
        Deque<String> path = new ArrayDeque<>();
        for (String module : graph.keySet()) {
            visit(module, graph, state, path);
        }
    }

    private void visit(String module, Map<String, List<String>> graph,
                       Map<String, Integer> state, Deque<String> path) {
        Integer mark = state.get(module);
        if (mark != null) {
            if (mark == 1) {
                List<String> cycle = new ArrayList<>(path);
                cycle.add(module);
                throw ApiException.conflict("MODULE_DEPENDENCY_CYCLE",
                        "模块依赖成环: " + String.join(" -> ", cycle));
            }
            return;
        }
        state.put(module, 1);
        path.addLast(module);
        for (String next : graph.getOrDefault(module, List.of())) {
            visit(next, graph, state, path);
        }
        path.removeLast();
        state.put(module, 2);
    }

    private static String fingerprintOf(MatrixRegisterRequest request) {
        StringBuilder fingerprint = new StringBuilder("matrix.register");
        for (MatrixRegisterRequest.RequirementEntry entry : request.firmwareRequirements()) {
            fingerprint.append('|').append(entry.firmwareVersion()).append(',').append(entry.module())
                    .append(',').append(entry.minVersion());
        }
        fingerprint.append('#');
        for (MatrixRegisterRequest.DependencyEntry entry : request.moduleDependencies()) {
            fingerprint.append('|').append(entry.module()).append(',').append(entry.dependsOn())
                    .append(',').append(entry.minVersion());
        }
        return fingerprint.toString();
    }
}
