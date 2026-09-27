package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BlockRecordView;
import com.example.starter.firmware.api.DeviceModulesView;
import com.example.starter.firmware.api.ExemptionHistoryView;
import com.example.starter.firmware.api.ExemptionView;
import com.example.starter.firmware.api.MatrixView;
import com.example.starter.firmware.api.MissingModuleView;
import com.example.starter.firmware.api.ModuleEntryView;
import com.example.starter.firmware.api.DeviceModulesReportRequest;
import com.example.starter.firmware.api.ExemptionRequest;
import com.example.starter.firmware.api.RegisterMatrixRequest;
import com.example.starter.firmware.domain.CompatibilityEngine;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DeviceModule;
import com.example.starter.firmware.domain.FirmwareMatrix;
import com.example.starter.firmware.domain.MissingModule;
import com.example.starter.firmware.domain.ModuleDependencyEdge;
import com.example.starter.firmware.domain.ModuleRequirement;
import com.example.starter.firmware.domain.ModuleVersion;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.BlockRecordRepository;
import com.example.starter.firmware.repo.DeviceModuleRepository;
import com.example.starter.firmware.repo.ExemptionRepository;
import com.example.starter.firmware.repo.MatrixRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固件模块兼容：矩阵登记（依赖闭包/环检测）、设备模块上报、设备组豁免与兼容校验。
 * 所有修订在一个事务内先验证完整后态再提交；时间一律 UTC。
 */
@Service
public class CompatibilityService {

    private final MatrixRepository matrixRepository;
    private final DeviceModuleRepository deviceModuleRepository;
    private final ExemptionRepository exemptionRepository;
    private final BlockRecordRepository blockRecordRepository;
    private final IdempotencyService idempotency;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final ReleaseRepository releaseRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CompatibilityService(MatrixRepository matrixRepository,
                                DeviceModuleRepository deviceModuleRepository,
                                ExemptionRepository exemptionRepository,
                                BlockRecordRepository blockRecordRepository,
                                IdempotencyService idempotency,
                                DeviceService deviceService,
                                ReleaseService releaseService,
                                ReleaseRepository releaseRepository,
                                ObjectMapper objectMapper, Clock clock) {
        this.matrixRepository = matrixRepository;
        this.deviceModuleRepository = deviceModuleRepository;
        this.exemptionRepository = exemptionRepository;
        this.blockRecordRepository = blockRecordRepository;
        this.idempotency = idempotency;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.releaseRepository = releaseRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ================= 矩阵登记 =================

    /**
     * 登记/修订固件模块需求矩阵。点分版本非法 422；依赖边合并进全局图后完整后态成环 422。
     * 同一固件重复登记为全量修订，矩阵版本加一；所有变更在同一事务提交，失败不留部分结果。
     */
    public MatrixView registerMatrix(RegisterMatrixRequest request) {
        Map<String, ModuleVersion> roots = new HashMap<>();
        for (RegisterMatrixRequest.RequirementInput req : request.requirements()) {
            ModuleVersion version = ModuleVersion.parse(req.minVersion(), "模块 " + req.module() + " 需求");
            roots.merge(req.module(), version, (old, incoming) ->
                    incoming.compareTo(old) > 0 ? incoming : old);
        }
        List<ModuleDependencyEdge> incomingEdges = new ArrayList<>();
        if (request.dependencies() != null) {
            Set<String> edgeKeys = new HashSet<>();
            for (RegisterMatrixRequest.DependencyInput dep : request.dependencies()) {
                ModuleVersion version = ModuleVersion.parse(dep.minVersion(),
                        "模块 " + dep.module() + " 对 " + dep.dependsOn() + " 的依赖");
                if (!edgeKeys.add(dep.module() + "" + dep.dependsOn())) {
                    throw ApiException.badRequest("DUPLICATE_DEPENDENCY_EDGE",
                            "依赖边重复声明: " + dep.module() + " -> " + dep.dependsOn());
                }
                incomingEdges.add(new ModuleDependencyEdge(dep.module(), dep.dependsOn(), version.raw()));
            }
        }
        String fingerprint = String.join("|", "matrix.register", request.firmwareVersion(),
                sortedPairFingerprint(new ArrayList<>(roots.entrySet()),
                        Map.Entry::getKey, e -> e.getValue().raw()),
                incomingEdges.stream().sorted((a, b) ->
                                (a.moduleName() + "" + a.dependsOn()).compareTo(b.moduleName() + "" + b.dependsOn()))
                        .map(e -> e.moduleName() + "=" + e.dependsOn() + "@" + e.minVersion())
                        .reduce("", (a, b) -> a + ";" + b));
        return idempotency.execute(request.requestId(), "matrix.register", fingerprint, () -> {
            // 0) 串行化所有矩阵登记：保证环检测基于完整后态、版本按提交顺序递增
            matrixRepository.lockGraph();
            // 1) 合并/抬高依赖边
            for (ModuleDependencyEdge edge : incomingEdges) {
                upsertEdge(edge);
            }
            // 2) 加载完整后态依赖图做环检测
            Map<String, Map<String, ModuleVersion>> adjacency = loadAdjacency();
            CompatibilityEngine.assertAcyclic(adjacency);
            // 3) 全量替换该固件直接需求
            List<ModuleRequirement> requirementRows = roots.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> new ModuleRequirement(request.firmwareVersion(), e.getKey(), e.getValue().raw()))
                    .toList();
            matrixRepository.replaceRequirements(request.firmwareVersion(), requirementRows);
            // 4) 矩阵头版本加一
            String nowUtc = Instant.now(clock).toString();
            int matrixVersion = matrixRepository.upsertMatrixVersion(request.firmwareVersion(), nowUtc);
            return buildView(request.firmwareVersion(), matrixVersion, nowUtc);
        }, MatrixView.class);
    }

    private void upsertEdge(ModuleDependencyEdge edge) {
        var existing = matrixRepository.findEdge(edge.moduleName(), edge.dependsOn());
        if (existing.isEmpty()) {
            try {
                matrixRepository.insertEdge(edge);
            } catch (DuplicateKeyException e) {
                existing = matrixRepository.findEdge(edge.moduleName(), edge.dependsOn());
            }
        }
        if (existing.isPresent()) {
            ModuleVersion current = ModuleVersion.parse(existing.get().minVersion(), "已登记依赖版本");
            ModuleVersion incoming = ModuleVersion.parse(edge.minVersion(), "新登记依赖版本");
            if (incoming.compareTo(current) > 0) {
                matrixRepository.updateEdgeMinVersion(
                        new ModuleDependencyEdge(edge.moduleName(), edge.dependsOn(), incoming.raw()));
            }
        }
    }

    private Map<String, Map<String, ModuleVersion>> loadAdjacency() {
        Map<String, Map<String, ModuleVersion>> adjacency = new HashMap<>();
        for (ModuleDependencyEdge edge : matrixRepository.findAllEdges()) {
            CompatibilityEngine.putEdge(adjacency, edge.moduleName(), edge.dependsOn(),
                    ModuleVersion.parse(edge.minVersion(), "依赖边版本"));
        }
        return adjacency;
    }

    public MatrixView getMatrix(String firmwareVersion) {
        FirmwareMatrix matrix = matrixRepository.findMatrix(firmwareVersion)
                .orElseThrow(() -> ApiException.notFound("MATRIX_NOT_FOUND",
                        "固件兼容矩阵不存在: " + firmwareVersion));
        return buildView(matrix.firmwareVersion(), matrix.matrixVersion(), matrix.updatedAtUtc());
    }

    private MatrixView buildView(String firmwareVersion, int matrixVersion, String updatedAtUtc) {
        List<MatrixView.RequirementView> requirements = matrixRepository.findRequirements(firmwareVersion)
                .stream()
                .map(r -> new MatrixView.RequirementView(r.moduleName(), r.minVersion()))
                .toList();
        List<MatrixView.DependencyView> dependencies = matrixRepository.findAllEdges().stream()
                .sorted((a, b) -> (a.moduleName() + "" + a.dependsOn())
                        .compareTo(b.moduleName() + "" + b.dependsOn()))
                .map(e -> new MatrixView.DependencyView(e.moduleName(), e.dependsOn(), e.minVersion()))
                .toList();
        return new MatrixView(firmwareVersion, matrixVersion, updatedAtUtc, requirements, dependencies);
    }

    // ================= 设备模块上报 =================

    /**
     * 全量上报设备模块清单（按提交顺序裁决，后报替换先报）。模块版本非法 422；
     * 设备不存在 404；上报版本加一，只影响后续拉取，不改写历史任务。
     */
    public DeviceModulesView reportModules(String deviceId, DeviceModulesReportRequest request) {
        Device device = deviceService.findDevice(deviceId);
        Map<String, ModuleVersion> modules = new HashMap<>();
        for (DeviceModulesReportRequest.ModuleVersionInput input : request.modules()) {
            ModuleVersion version = ModuleVersion.parse(input.version(), "模块 " + input.module());
            ModuleVersion old = modules.put(input.module(), version);
            if (old != null) {
                throw ApiException.badRequest("DUPLICATE_MODULE", "清单中模块重复: " + input.module());
            }
        }
        String fingerprint = String.join("|", "device.modules", deviceId,
                sortedPairFingerprint(new ArrayList<>(modules.entrySet()),
                        Map.Entry::getKey, e -> e.getValue().raw()));
        return idempotency.execute(request.requestId(), "device.modules", fingerprint, () -> {
            // 持设备行锁至事务结束：同设备并发上报按提交顺序串行裁决，上报版本严格递增不丢失
            Device lockedDevice = deviceService.findDeviceForUpdate(deviceId);
            String nowUtc = Instant.now(clock).toString();
            List<DeviceModule> rows = modules.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> new DeviceModule(e.getKey(), e.getValue().raw()))
                    .toList();
            int reportVersion = deviceModuleRepository.replaceReport(lockedDevice.deviceId(), rows, nowUtc);
            return new DeviceModulesView(deviceId, reportVersion, nowUtc, toEntryViews(rows));
        }, DeviceModulesView.class);
    }

    public DeviceModulesView getDeviceModules(String deviceId) {
        deviceService.findDevice(deviceId);
        Integer version = deviceModuleRepository.findReportVersion(deviceId);
        if (version == null) {
            throw ApiException.notFound("MODULE_REPORT_NOT_FOUND", "设备尚未上报模块清单: " + deviceId);
        }
        return new DeviceModulesView(deviceId, version,
                deviceModuleRepository.findReportedAtUtc(deviceId),
                toEntryViews(deviceModuleRepository.findByDevice(deviceId)));
    }

    // ================= 豁免 =================

    /**
     * 配置/修订发布单设备组模块豁免。必须显式列出模块与到期时刻（UTC），
     * 到期后自动失效（左闭右开），不绕过签名/失败率暂停等既有门禁；修订后豁免版本加一。
     */
    public ExemptionView configureExemption(long releaseId, ExemptionRequest request) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        if (order.status() == ReleaseStatus.CANCELLED) {
            throw ApiException.conflict("RELEASE_CANCELLED", "发布单已取消，不能配置模块豁免");
        }
        parseFutureInstant(request.expiresAtUtc(), "expiresAtUtc");
        List<String> modules = normalizeModules(request.modules());
        String fingerprint = String.join("|", "exemption.configure", String.valueOf(releaseId),
                request.groupName(), String.join(",", modules), request.expiresAtUtc());
        return idempotency.execute(request.requestId(), "exemption.configure", fingerprint, () -> {
            // 锁发布单行串行化同发布单的豁免配置，避免首版行不存在时 FOR UPDATE 锁空导致并发插入撞唯一约束
            ReleaseOrder locked = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (locked.status() == ReleaseStatus.CANCELLED) {
                throw ApiException.conflict("RELEASE_CANCELLED", "发布单已取消，不能配置模块豁免");
            }
            String nowUtc = Instant.now(clock).toString();
            String modulesJson = writeStringList(modules);
            var current = exemptionRepository.findCurrentForUpdate(releaseId, request.groupName());
            if (current.isEmpty()) {
                exemptionRepository.insertFirst(releaseId, request.groupName(), modulesJson,
                        request.expiresAtUtc(), nowUtc);
                return buildExemptionView(releaseId, request.groupName(), 1, modules,
                        request.expiresAtUtc(), nowUtc);
            }
            exemptionRepository.revise(releaseId, request.groupName(), current.get().version(),
                    modulesJson, request.expiresAtUtc(), nowUtc);
            return buildExemptionView(releaseId, request.groupName(), current.get().version() + 1, modules,
                    request.expiresAtUtc(), nowUtc);
        }, ExemptionView.class);
    }

    public ExemptionView getExemption(long releaseId, String groupName) {
        releaseService.findOrder(releaseId);
        ExemptionRepository.ExemptionRow row = exemptionRepository.findCurrent(releaseId, groupName)
                .orElseThrow(() -> ApiException.notFound("EXEMPTION_NOT_FOUND",
                        "发布单 %d 的设备组 %s 未配置模块豁免".formatted(releaseId, groupName)));
        List<String> modules = readStringList(row.modulesJson());
        return buildExemptionView(releaseId, groupName, row.version(), modules,
                row.expiresAtUtc(), row.createdAtUtc());
    }

    public List<ExemptionHistoryView> getExemptionHistory(long releaseId, String groupName) {
        releaseService.findOrder(releaseId);
        return exemptionRepository.findHistory(releaseId, groupName).stream()
                .map(row -> new ExemptionHistoryView(row.version(), readStringList(row.modulesJson()),
                        row.expiresAtUtc(), row.createdAtUtc(), row.supersededAtUtc()))
                .toList();
    }

    private ExemptionView buildExemptionView(long releaseId, String groupName, int version,
                                             List<String> modules, String expiresAtUtc,
                                             String createdAtUtc) {
        Instant now = Instant.now(clock);
        Instant expiresAt = Instant.parse(expiresAtUtc);
        Instant createdAt = Instant.parse(createdAtUtc);
        boolean active = now.isBefore(expiresAt);
        // 时长：要求值 = 到期 - 创建；差额（剩余）= 到期 - 当前，到期后 <= 0
        long durationSeconds = Duration.between(createdAt, expiresAt).getSeconds();
        long remainingSeconds = Duration.between(now, expiresAt).getSeconds();
        return new ExemptionView(releaseId, groupName, version, modules, expiresAtUtc, createdAtUtc,
                active, durationSeconds, remainingSeconds);
    }

    private List<String> normalizeModules(List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            throw ApiException.badRequest("EMPTY_EXEMPTION_MODULES", "豁免必须显式列出至少一个模块");
        }
        List<String> sorted = modules.stream().map(String::trim).sorted().distinct().toList();
        if (sorted.size() != modules.size()) {
            throw ApiException.badRequest("DUPLICATE_EXEMPTION_MODULE", "豁免模块不可重复列出");
        }
        if (sorted.stream().anyMatch(String::isBlank)) {
            throw ApiException.badRequest("BLANK_EXEMPTION_MODULE", "豁免模块名不能为空");
        }
        return sorted;
    }

    private Instant parseFutureInstant(String value, String field) {
        try {
            Instant parsed = Instant.parse(value);
            if (!parsed.isAfter(Instant.now(clock))) {
                throw ApiException.badRequest("EXEMPTION_ALREADY_EXPIRED", field + " 必须晚于当前 UTC 时刻");
            }
            return parsed;
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_INSTANT", field + " 不是合法的 UTC ISO-8601 时刻: " + value);
        }
    }

    // ================= 拉取兼容校验 =================

    /**
     * 兼容校验结果。compatible 时携带固化快照；blocked 时携带稳定排序的缺失明细。
     */
    public record Evaluation(boolean compatible, List<MissingModule> missing, String deviceSummaryJson,
                             Integer matrixVersion, Integer exemptionVersion) {
    }

    /**
     * 校验设备相对目标固件的完整依赖集合。调用方须已通过 ACTIVE/来源版本/分桶等既有门禁并持有发布单行锁。
     */
    public Evaluation evaluate(ReleaseOrder order, Device device) {
        Instant now = Instant.now(clock);
        Integer matrixVersion = null;
        Map<String, ModuleVersion> required = new HashMap<>();
        FirmwareMatrix matrix = matrixRepository.findMatrix(order.toVersion()).orElse(null);
        if (matrix != null) {
            matrixVersion = matrix.matrixVersion();
            Map<String, Map<String, ModuleVersion>> adjacency = loadAdjacency();
            Map<String, ModuleVersion> roots = new HashMap<>();
            for (ModuleRequirement req : matrixRepository.findRequirements(order.toVersion())) {
                roots.put(req.moduleName(), ModuleVersion.parse(req.minVersion(), "矩阵需求版本"));
            }
            required = CompatibilityEngine.closure(roots, adjacency);
        }

        List<DeviceModule> reported = deviceModuleRepository.findByDevice(device.deviceId());
        String summaryJson = reported.isEmpty() ? null : writeSummary(reported);
        Map<String, ModuleVersion> installed = new HashMap<>();
        for (DeviceModule module : reported) {
            installed.put(module.moduleName(), ModuleVersion.parse(module.version(), "设备模块版本"));
        }

        Integer exemptionVersion = null;
        Set<String> exemptedModules;
        if (device.groupName() != null) {
            var exemption = exemptionRepository.findCurrent(order.id(), device.groupName());
            if (exemption.isPresent() && now.isBefore(Instant.parse(exemption.get().expiresAtUtc()))) {
                exemptionVersion = exemption.get().version();
                exemptedModules = new HashSet<>(readStringList(exemption.get().modulesJson()));
            } else {
                exemptedModules = Set.of();
            }
        } else {
            exemptedModules = Set.of();
        }

        List<MissingModule> missing = required.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(e -> !exemptedModules.contains(e.getKey()))
                .map(e -> {
                    ModuleVersion actual = installed.get(e.getKey());
                    if (actual == null || actual.compareTo(e.getValue()) < 0) {
                        return new MissingModule(e.getKey(),
                                actual == null ? null : actual.raw(), e.getValue().raw());
                    }
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        return new Evaluation(missing.isEmpty(), missing, summaryJson, matrixVersion, exemptionVersion);
    }

    /**
     * 落 MODULE_BLOCKED 诊断记录（在拉取事务内，失败回滚不占 requestId）。
     */
    public long recordBlock(ReleaseOrder order, Device device, String requestId, Evaluation evaluation) {
        return blockRecordRepository.insert(order.id(), device.deviceId(), requestId,
                evaluation.matrixVersion(), evaluation.exemptionVersion(),
                evaluation.deviceSummaryJson(), writeMissing(evaluation.missing()),
                Instant.now(clock).toString());
    }

    public List<BlockRecordView> listBlocksByRelease(long releaseId) {
        releaseService.findOrder(releaseId);
        return blockRecordRepository.findByRelease(releaseId).stream().map(this::toBlockView).toList();
    }

    public List<BlockRecordView> listBlocksByDevice(String deviceId) {
        deviceService.findDevice(deviceId);
        return blockRecordRepository.findByDevice(deviceId).stream().map(this::toBlockView).toList();
    }

    private BlockRecordView toBlockView(BlockRecordRepository.BlockRow row) {
        List<ModuleEntryView> summary = row.deviceSummary() == null
                ? null : readSummary(row.deviceSummary());
        List<MissingModuleView> missing = readMissing(row.missingModulesJson()).stream()
                .map(m -> new MissingModuleView(m.moduleName(), m.deviceVersion(), m.requiredVersion()))
                .toList();
        return new BlockRecordView(row.id(), row.releaseId(), row.deviceId(), row.requestId(),
                row.matrixVersion(), row.exemptionVersion(), summary, missing, missing.size(),
                row.blockedAtUtc());
    }

    // ================= JSON / 排序工具 =================

    private List<ModuleEntryView> toEntryViews(List<DeviceModule> modules) {
        return modules.stream()
                .sorted((a, b) -> a.moduleName().compareTo(b.moduleName()))
                .map(m -> new ModuleEntryView(m.moduleName(), m.version()))
                .toList();
    }

    public List<ModuleEntryView> summaryToViews(String summaryJson) {
        return summaryJson == null ? null : readSummary(summaryJson);
    }

    private String writeSummary(List<DeviceModule> modules) {
        try {
            List<ModuleEntryView> views = toEntryViews(modules);
            return objectMapper.writeValueAsString(views);
        } catch (Exception e) {
            throw new IllegalStateException("设备模块摘要序列化失败", e);
        }
    }

    private List<ModuleEntryView> readSummary(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ModuleEntryView>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("设备模块摘要反序列化失败", e);
        }
    }

    private String writeMissing(List<MissingModule> missing) {
        try {
            List<MissingModuleView> views = missing.stream()
                    .map(m -> new MissingModuleView(m.moduleName(), m.deviceVersion(), m.requiredVersion()))
                    .toList();
            return objectMapper.writeValueAsString(views);
        } catch (Exception e) {
            throw new IllegalStateException("缺失模块明细序列化失败", e);
        }
    }

    private List<MissingModule> readMissing(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<MissingModuleView>>() {
            }).stream()
                    .map(v -> new MissingModule(v.module(), v.deviceVersion(), v.requiredVersion()))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("缺失模块明细反序列化失败", e);
        }
    }

    private String writeStringList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException("豁免模块序列化失败", e);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("豁免模块反序列化失败", e);
        }
    }

    private <T> String sortedPairFingerprint(List<Map.Entry<String, T>> entries,
                                             java.util.function.Function<Map.Entry<String, T>, String> key,
                                             java.util.function.Function<Map.Entry<String, T>, String> value) {
        return entries.stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> key.apply(e) + "@" + value.apply(e))
                .reduce("", (a, b) -> a + ";" + b);
    }
}
