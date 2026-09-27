package com.example.starter.firmware.service;

import com.example.starter.firmware.api.FirmwareRequirementView;
import com.example.starter.firmware.api.MatrixView;
import com.example.starter.firmware.api.ModuleDependencyView;
import com.example.starter.firmware.api.RegisterFirmwareRequirementRequest;
import com.example.starter.firmware.api.RegisterModuleDependencyRequest;
import com.example.starter.firmware.domain.ModuleDependency;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.MatrixRepository;
import com.example.starter.firmware.support.ModuleVersion;
import com.example.starter.firmware.support.UtcTimes;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 兼容矩阵登记：固件模块需求与模块间依赖边。
 * 登记依赖时先在含新边的完整后态上做环检测（含自依赖），成环返回 422 并整体回滚，矩阵版本不增加。
 * 每次成功登记（固件需求或模块依赖）矩阵版本加一；拉取时按当时版本固化。
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
     * 登记（或覆盖）一条固件模块需求。点分数字版本非法返回 422；成功后矩阵版本加一。
     */
    public MatrixView registerFirmwareRequirement(RegisterFirmwareRequirementRequest request) {
        ModuleVersion.parse(request.firmwareVersion());
        ModuleVersion.parse(request.minVersion());
        String fingerprint = String.join("|", "matrix.firmwareRequirement", request.model(),
                request.firmwareVersion(), request.moduleName(), request.minVersion());
        return idempotency.execute(request.requestId(), "matrix.firmwareRequirement", fingerprint, () -> {
            String now = UtcTimes.now(clock);
            matrixRepository.upsertFirmwareRequirement(request.model(), request.firmwareVersion(),
                    request.moduleName(), request.minVersion(), now);
            matrixRepository.bumpVersion(now);
            return currentView();
        }, MatrixView.class);
    }

    /**
     * 登记（或覆盖）一条模块依赖边。先对含新边的完整后态做环检测，成环（含自依赖）返回 422 并回滚。
     */
    public MatrixView registerModuleDependency(RegisterModuleDependencyRequest request) {
        String minVersion = request.effectiveMinVersion();
        ModuleVersion.parse(minVersion);
        String fingerprint = String.join("|", "matrix.moduleDependency", request.moduleName(),
                request.dependsOn(), minVersion);
        return idempotency.execute(request.requestId(), "matrix.moduleDependency", fingerprint, () -> {
            Map<String, List<Edge>> graph = loadGraph();
            // 构造后态：覆盖同一条边的最低版本
            graph.computeIfAbsent(request.moduleName(), k -> new ArrayList<>())
                    .removeIf(edge -> edge.to().equals(request.dependsOn()));
            graph.computeIfAbsent(request.moduleName(), k -> new ArrayList<>())
                    .add(new Edge(request.dependsOn(), minVersion));
            ensureAcyclic(graph, request.moduleName(), request.dependsOn());
            String now = UtcTimes.now(clock);
            matrixRepository.upsertModuleDependency(request.moduleName(), request.dependsOn(), minVersion, now);
            matrixRepository.bumpVersion(now);
            return currentView();
        }, MatrixView.class);
    }

    public MatrixView currentView() {
        return new MatrixView(matrixRepository.currentVersion(),
                matrixRepository.updatedAtUtc().orElse(null),
                matrixRepository.countFirmwareRequirements(),
                matrixRepository.countModuleDependencies());
    }

    public List<FirmwareRequirementView> listFirmwareRequirements() {
        return matrixRepository.findAllFirmwareRequirements().stream()
                .map(FirmwareRequirementView::of).toList();
    }

    public List<ModuleDependencyView> listModuleDependencies() {
        return matrixRepository.findAllDependencies().stream()
                .map(ModuleDependencyView::of).toList();
    }

    private record Edge(String to, String minVersion) {
    }

    private Map<String, List<Edge>> loadGraph() {
        Map<String, List<Edge>> graph = new HashMap<>();
        for (ModuleDependency dependency : matrixRepository.findAllDependencies()) {
            graph.computeIfAbsent(dependency.moduleName(), k -> new ArrayList<>())
                    .add(new Edge(dependency.dependsOn(), dependency.minVersion()));
        }
        return graph;
    }

    /**
     * Kahn 拓扑环检测：后态无法完成全部节点的拓扑排序即存在环（自依赖同理被检出）。
     * 错误信息稳定给出成环相关节点，便于区分失败原因。
     */
    private void ensureAcyclic(Map<String, List<Edge>> graph, String newFrom, String newTo) {
        Map<String, Integer> indegree = new HashMap<>();
        for (String node : graph.keySet()) {
            indegree.putIfAbsent(node, 0);
        }
        for (List<Edge> edges : graph.values()) {
            for (Edge edge : edges) {
                indegree.putIfAbsent(edge.to(), 0);
                indegree.merge(edge.to(), 1, Integer::sum);
            }
        }
        Set<String> resolved = new HashSet<>();
        List<String> queue = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        int head = 0;
        while (head < queue.size()) {
            String node = queue.get(head++);
            resolved.add(node);
            for (Edge edge : graph.getOrDefault(node, List.of())) {
                int next = indegree.merge(edge.to(), -1, Integer::sum);
                if (next == 0) {
                    queue.add(edge.to());
                }
            }
        }
        if (resolved.size() != indegree.size()) {
            List<String> cyclic = indegree.keySet().stream()
                    .filter(node -> !resolved.contains(node))
                    .sorted()
                    .toList();
            throw ApiException.unprocessable("MODULE_DEPENDENCY_CYCLE",
                    "登记依赖 " + newFrom + " -> " + newTo + " 会形成模块依赖环，涉及模块: "
                            + String.join(",", cyclic));
        }
    }

    /**
     * 计算某型号某固件版本的完整依赖集合：直接需求沿模块依赖边传递闭包展开，
     * 同一模块经多条路径要求时取最高最低版本。返回结果按模块名稳定排序。
     * 调用前所有依赖边已在登记时保证无环；此处再以步数上限防御性兜底。
     */
    public static LinkedHashMap<String, ModuleVersion> resolveRequiredModules(
            Map<String, List<ModuleDependencyEdge>> dependencyGraph,
            Map<String, ModuleVersion> directRequirements) {
        LinkedHashMap<String, ModuleVersion> required = new LinkedHashMap<>();
        directRequirements.forEach((module, version) ->
                required.merge(module, version, MatrixService::higher));
        int maxRounds = dependencyGraph.size() + directRequirements.size() + 1;
        for (int round = 0; round < maxRounds; round++) {
            boolean changed = false;
            for (String module : List.copyOf(required.keySet())) {
                for (ModuleDependencyEdge edge : dependencyGraph.getOrDefault(module, List.of())) {
                    ModuleVersion previous = required.get(edge.to());
                    if (previous == null || previous.isLowerThan(edge.minVersion())) {
                        required.put(edge.to(), edge.minVersion());
                        changed = true;
                    }
                }
            }
            if (!changed) {
                LinkedHashMap<String, ModuleVersion> sorted = new LinkedHashMap<>();
                required.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
                return sorted;
            }
        }
        throw new IllegalStateException("模块依赖闭包展开超过上限，依赖图可能存在环");
    }

    private static ModuleVersion higher(ModuleVersion a, ModuleVersion b) {
        return a.isLowerThan(b) ? b : a;
    }

    /**
     * 模块依赖边的值对象，供闭包计算使用。
     */
    public record ModuleDependencyEdge(String from, String to, ModuleVersion minVersion) {
    }
}
