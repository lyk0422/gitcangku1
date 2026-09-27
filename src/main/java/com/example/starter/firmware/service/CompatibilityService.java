package com.example.starter.firmware.service;

import com.example.starter.firmware.domain.BlockedModule;
import com.example.starter.firmware.domain.CompatibilityCheck;
import com.example.starter.firmware.domain.CompatibilityOutcome;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.FirmwareRequirement;
import com.example.starter.firmware.domain.ModuleBlockReason;
import com.example.starter.firmware.domain.ModuleDependency;
import com.example.starter.firmware.domain.ModuleExemption;
import com.example.starter.firmware.domain.ModuleSnapshot;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.repo.CompatibilityCheckRepository;
import com.example.starter.firmware.repo.DeviceModuleRepository;
import com.example.starter.firmware.repo.ExemptionRepository;
import com.example.starter.firmware.repo.MatrixRepository;
import com.example.starter.firmware.support.ModuleVersion;
import com.example.starter.firmware.support.UtcTimes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固件模块兼容校验：拉取任务前对目标固件的完整依赖集合逐项校验。
 * 完整依赖集合由固件直接需求沿模块依赖边传递闭包得到（多路径取最高最低版本）。
 * 缺失模块（设备版本为 null）或低版本模块先计入阻断；其中被设备组内、未到期豁免显式覆盖的模块
 * 转为豁免放行；仍有阻断项则 MODULE_BLOCKED，不创建任务且不计入失败率。
 * 豁免只作用于模块兼容，不绕过 ACTIVE/分桶/来源版本/失败率暂停等既有门禁（这些在调用方先行判定）。
 */
@Service
public class CompatibilityService {

    private final MatrixRepository matrixRepository;
    private final DeviceModuleRepository deviceModuleRepository;
    private final ExemptionRepository exemptionRepository;
    private final CompatibilityCheckRepository checkRepository;
    private final ObjectMapper objectMapper;

    public CompatibilityService(MatrixRepository matrixRepository,
                                DeviceModuleRepository deviceModuleRepository,
                                ExemptionRepository exemptionRepository,
                                CompatibilityCheckRepository checkRepository,
                                ObjectMapper objectMapper) {
        this.matrixRepository = matrixRepository;
        this.deviceModuleRepository = deviceModuleRepository;
        this.exemptionRepository = exemptionRepository;
        this.checkRepository = checkRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 校验结论（不含持久化）。
     *
     * @param outcome          GRANTED/EXEMPTION 可下发；BLOCKED 阻断
     * @param matrixVersion    校验时矩阵版本
     * @param exemptionVersion 校验时发布单豁免版本
     * @param snapshots        设备模块清单快照，按模块名排序
     * @param blocked          阻断明细，按模块名排序
     * @param exemptedModules  实际依靠豁免放行的模块名，按模块名排序
     */
    public record Decision(CompatibilityOutcome outcome, int matrixVersion, int exemptionVersion,
                           List<ModuleSnapshot> snapshots, List<BlockedModule> blocked,
                           List<String> exemptedModules, String snapshotsJson) {
    }

    /**
     * 在调用方已持有发布单行锁、且既有门禁（ACTIVE/型号/来源版本/分桶）均通过的前提下执行兼容校验。
     */
    public Decision evaluate(ReleaseOrder order, Device device, Instant now) {
        int matrixVersion = matrixRepository.currentVersion();

        Map<String, ModuleVersion> direct = new LinkedHashMap<>();
        for (FirmwareRequirement requirement :
                matrixRepository.findFirmwareRequirements(order.model(), order.toVersion())) {
            direct.merge(requirement.moduleName(), ModuleVersion.parse(requirement.minVersion()),
                    (a, b) -> a.isLowerThan(b) ? b : a);
        }
        Map<String, List<MatrixService.ModuleDependencyEdge>> graph = loadGraph();
        LinkedHashMap<String, ModuleVersion> required =
                MatrixService.resolveRequiredModules(graph, direct);

        List<ModuleSnapshot> snapshots = deviceModuleRepository.findSnapshotsByDevice(device.deviceId());
        Map<String, ModuleSnapshot> deviceModules = new HashMap<>();
        for (ModuleSnapshot snapshot : snapshots) {
            deviceModules.put(snapshot.moduleName(), snapshot);
        }

        Map<String, Instant> groupExemptions = loadActiveGroupExemptions(order, device.deviceGroup(), now);

        List<BlockedModule> blocked = new ArrayList<>();
        List<String> exempted = new ArrayList<>();
        for (Map.Entry<String, ModuleVersion> entry : required.entrySet()) {
            String module = entry.getKey();
            ModuleVersion requiredVersion = entry.getValue();
            ModuleSnapshot installed = deviceModules.get(module);
            ModuleBlockReason reason = null;
            String deviceVersion = null;
            if (installed == null) {
                reason = ModuleBlockReason.MISSING;
            } else {
                deviceVersion = installed.version();
                if (ModuleVersion.parse(installed.version()).isLowerThan(requiredVersion)) {
                    reason = ModuleBlockReason.VERSION_TOO_LOW;
                }
            }
            if (reason == null) {
                continue;
            }
            // 仅当该模块确为阻断项且被同设备组、未到期的显式豁免覆盖时才放行
            Instant expiresAt = groupExemptions.get(module);
            if (expiresAt != null && now.isBefore(expiresAt)) {
                exempted.add(module);
            } else {
                blocked.add(new BlockedModule(module, deviceVersion, requiredVersion.raw(), reason));
            }
        }
        blocked.sort((a, b) -> a.moduleName().compareTo(b.moduleName()));
        exempted.sort(String::compareTo);

        CompatibilityOutcome outcome = blocked.isEmpty()
                ? (exempted.isEmpty() ? CompatibilityOutcome.GRANTED : CompatibilityOutcome.EXEMPTION)
                : CompatibilityOutcome.BLOCKED;
        return new Decision(outcome, matrixVersion, order.exemptionVersion(), snapshots, blocked, exempted,
                writeSnapshots(snapshots));
    }

    /**
     * 固化一条判定证据（下发、阻断、豁免放行均固化），与任务创建处于调用方同一事务。
     */
    public CompatibilityCheck persist(Decision decision, ReleaseOrder order, Device device,
                                      String requestId, Instant now) {
        long id = checkRepository.insert(new CompatibilityCheck(0, order.id(), device.deviceId(),
                decision.outcome(), decision.matrixVersion(), decision.exemptionVersion(),
                decision.snapshots(), decision.blocked(), decision.exemptedModules(),
                requestId, now.toString()));
        return checkRepository.findById(id).orElseThrow(() -> new IllegalStateException("兼容判定证据写入后读取失败"));
    }

    private Map<String, List<MatrixService.ModuleDependencyEdge>> loadGraph() {
        Map<String, List<MatrixService.ModuleDependencyEdge>> graph = new HashMap<>();
        for (ModuleDependency dependency : matrixRepository.findAllDependencies()) {
            graph.computeIfAbsent(dependency.moduleName(), k -> new ArrayList<>())
                    .add(new MatrixService.ModuleDependencyEdge(dependency.moduleName(), dependency.dependsOn(),
                            ModuleVersion.parse(dependency.minVersion())));
        }
        return graph;
    }

    /**
     * 取发布单当前豁免版本中、指定设备组的全部条目并解析到期时刻；已到期条目不放入结果（左闭右开）。
     * 设备无组名（null）不享受任何豁免。
     */
    private Map<String, Instant> loadActiveGroupExemptions(ReleaseOrder order, String deviceGroup, Instant now) {
        Map<String, Instant> result = new HashMap<>();
        if (deviceGroup == null || order.exemptionVersion() <= 0) {
            return result;
        }
        for (ModuleExemption exemption : exemptionRepository.findByVersion(order.id(), order.exemptionVersion())) {
            if (!deviceGroup.equals(exemption.deviceGroup())) {
                continue;
            }
            Instant expiresAt = UtcTimes.parse(exemption.expiresAtUtc());
            if (now.isBefore(expiresAt)) {
                result.put(exemption.moduleName(), expiresAt);
            }
        }
        return result;
    }

    private String writeSnapshots(List<ModuleSnapshot> snapshots) {
        try {
            return objectMapper.writeValueAsString(snapshots);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("设备模块快照序列化失败", e);
        }
    }

    /**
     * 按设备查询兼容判定历史（只读），按提交顺序（ID 升序）返回。
     */
    public List<CompatibilityCheck> listByDevice(String deviceId) {
        return checkRepository.findByDevice(deviceId);
    }

    /**
     * 按发布单查询兼容判定历史（只读），按提交顺序（ID 升序）返回。
     */
    public List<CompatibilityCheck> listByRelease(long releaseId) {
        return checkRepository.findByRelease(releaseId);
    }
}
