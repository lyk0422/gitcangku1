package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceResultView;
import com.example.starter.firmware.api.GateView;
import com.example.starter.firmware.api.RehearsalHistoryResponse;
import com.example.starter.firmware.api.RehearsalResultRequest;
import com.example.starter.firmware.api.RehearsalView;
import com.example.starter.firmware.api.StartRehearsalRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.RehearsalDevice;
import com.example.starter.firmware.domain.RehearsalStatus;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseGate;
import com.example.starter.firmware.domain.RollbackRehearsal;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.GateRepository;
import com.example.starter.firmware.repo.RehearsalRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 固件回滚演练与正式投放门禁。
 *
 * <p>开始演练：校验规范设备集合（非空、去重排序）、版本相容（设备均登记、型号一致、当前版本等于目标版本、
 * 回退版本不同于目标版本）、设备无既有正式投放任务；写入 RUNNING 演练头与设备行。
 *
 * <p>提交结果：先锁演练行并对完整集合做最终校验（集合完全一致、无重复、结果合法、全部成功、结束不早于开始），
 * 再在同一事务内整批写入结果、置 SUCCEEDED 并授予 READY_FOR_RELEASE；任一校验失败整批回滚，不留半成品。
 *
 * <p>所有时刻为 UTC，区间按左闭右开 [startedAt, endedAt) 解释。演练、设备结果、版本与门禁结论不可变。
 */
@Service
public class RehearsalService {

    /** active_key 内部分隔符：0x1F 不出现在型号/版本业务字符中，仅用于库内唯一约束键。 */
    private static final String ACTIVE_KEY_SEPARATOR = "\u001F";

    private final RehearsalRepository rehearsalRepository;
    private final GateRepository gateRepository;
    private final TaskRepository taskRepository;
    private final DeviceService deviceService;
    private final IdempotencyService idempotency;
    private final Fingerprints fingerprints;
    private final Clock clock;

    public RehearsalService(RehearsalRepository rehearsalRepository, GateRepository gateRepository,
                            TaskRepository taskRepository, DeviceService deviceService,
                            IdempotencyService idempotency, Fingerprints fingerprints, Clock clock) {
        this.rehearsalRepository = rehearsalRepository;
        this.gateRepository = gateRepository;
        this.taskRepository = taskRepository;
        this.deviceService = deviceService;
        this.idempotency = idempotency;
        this.fingerprints = fingerprints;
        this.clock = clock;
    }

    public RehearsalView start(StartRehearsalRequest request) {
        List<String> normalized = normalizeDeviceIds(request.deviceIds());
        String fingerprint = fingerprints.startRehearsal(request.operator(), request.model(),
                request.targetVersion(), request.rollbackVersion(), normalized);
        return idempotency.execute(request.requestId(), "rehearsal.start", fingerprint, () -> {
            validateStart(request, normalized);
            String startedAt = Instant.now(clock).toString();
            String activeKey = request.model() + ACTIVE_KEY_SEPARATOR + request.targetVersion();
            long rehearsalId;
            try {
                rehearsalId = rehearsalRepository.insert(request.model(), request.targetVersion(),
                        request.rollbackVersion(), request.operator(), normalized.size(), startedAt, activeKey);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("REHEARSAL_ALREADY_RUNNING",
                        "型号 " + request.model() + " 目标版本 " + request.targetVersion()
                                + " 已存在未完成演练，须先完成或放弃后再开始");
            }
            for (int i = 0; i < normalized.size(); i++) {
                rehearsalRepository.insertDevice(rehearsalId, normalized.get(i), i);
            }
            return viewOf(rehearsalRepository.findById(rehearsalId).orElseThrow(),
                    rehearsalRepository.findDevices(rehearsalId));
        }, RehearsalView.class);
    }

    public RehearsalView submitResults(long rehearsalId, RehearsalResultRequest request) {
        // 结果集合规范化（按设备ID字典序），用于指纹；业务枚举合法性在事务内再校验
        List<Map<String, String>> normalizedResults = request.results().stream()
                .map(dr -> Map.of("deviceId", dr.deviceId(), "result", dr.result()))
                .sorted(Comparator.comparing(m -> m.get("deviceId")))
                .toList();
        long distinctDevices = request.results().stream()
                .map(RehearsalResultRequest.DeviceResult::deviceId).distinct().count();
        if (distinctDevices != request.results().size()) {
            throw ApiException.unprocessable("RESULT_DEVICE_DUPLICATE",
                    "结果集合包含重复设备：实际 " + request.results().size() + " 条，去重后 " + distinctDevices + " 台");
        }
        String fingerprint = fingerprints.submitRehearsal(request.operator(), rehearsalId, normalizedResults);
        return idempotency.execute(request.requestId(), "rehearsal.submit", fingerprint, () -> {
            RollbackRehearsal rehearsal = rehearsalRepository.findByIdForUpdate(rehearsalId)
                    .orElseThrow(() -> ApiException.notFound("REHEARSAL_NOT_FOUND",
                            "演练不存在: " + rehearsalId));
            if (rehearsal.status() == RehearsalStatus.SUCCEEDED) {
                throw ApiException.conflict("REHEARSAL_FINISHED",
                        "演练已结束（SUCCEEDED），结果不可改写: " + rehearsalId);
            }
            if (!rehearsal.operator().equals(request.operator())) {
                throw ApiException.conflict("REHEARSAL_OPERATOR_CONFLICT",
                        "操作者与演练发起人不一致: " + rehearsal.operator());
            }
            List<RehearsalDevice> canonical = rehearsalRepository.findDevices(rehearsalId);
            Map<String, ReceiptResult> submitted = validateAndParseResults(request, canonical);

            int failedCount = 0;
            for (ReceiptResult result : submitted.values()) {
                if (result == ReceiptResult.FAILED) {
                    failedCount++;
                }
            }
            if (failedCount > 0) {
                int successCount = canonical.size() - failedCount;
                throw ApiException.unprocessable("REHEARSAL_FAILED",
                        "演练存在失败设备：实际成功 " + successCount + " 台，要求 " + canonical.size()
                                + " 台，差额 " + failedCount + " 台；本次结果整批驳回，不产生部分就绪状态");
            }

            Instant startedAt = Instant.parse(rehearsal.startedAtUtc());
            Instant endedAt = Instant.now(clock);
            if (endedAt.isBefore(startedAt)) {
                throw ApiException.unprocessable("REHEARSAL_END_BEFORE_START",
                        "演练结束时刻 " + endedAt + " 早于开始时刻 " + startedAt + "（UTC，区间左闭右开）");
            }
            long durationSeconds = Duration.between(startedAt, endedAt).toSeconds();
            String endedAtUtc = endedAt.toString();

            // 完整最终状态校验通过后，整批结果与门禁在同一事务提交
            for (RehearsalDevice device : canonical) {
                rehearsalRepository.recordDeviceResult(rehearsalId, device.deviceId(),
                        submitted.get(device.deviceId()), endedAtUtc);
            }
            rehearsalRepository.markSucceeded(rehearsalId, canonical.size(), 0, endedAtUtc, durationSeconds);
            grantGateIfAbsent(rehearsal, canonical.size(), endedAtUtc);

            return viewOf(rehearsalRepository.findById(rehearsalId).orElseThrow(),
                    rehearsalRepository.findDevices(rehearsalId));
        }, RehearsalView.class);
    }

    public RehearsalView detail(long rehearsalId) {
        RollbackRehearsal rehearsal = rehearsalRepository.findById(rehearsalId)
                .orElseThrow(() -> ApiException.notFound("REHEARSAL_NOT_FOUND", "演练不存在: " + rehearsalId));
        return viewOf(rehearsal, rehearsalRepository.findDevices(rehearsalId));
    }

    /**
     * 历史查询：按开始时刻、演练ID稳定排序；只读不改写状态。
     */
    public RehearsalHistoryResponse history(String model, String targetVersion) {
        List<RehearsalView> views = rehearsalRepository.findAll().stream()
                .filter(r -> model == null || model.isBlank() || r.model().equals(model))
                .filter(r -> targetVersion == null || targetVersion.isBlank()
                        || r.targetVersion().equals(targetVersion))
                .sorted((a, b) -> {
                    int byStart = a.startedAtUtc().compareTo(b.startedAtUtc());
                    return byStart != 0 ? byStart : Long.compare(a.id(), b.id());
                })
                .map(r -> viewOf(r, rehearsalRepository.findDevices(r.id())))
                .toList();
        return new RehearsalHistoryResponse(model, targetVersion, views);
    }

    /**
     * 门禁诊断查询：按授予顺序（ID）稳定排序，只读。
     */
    public List<GateView> gates(String model, String targetVersion) {
        String modelFilter = model == null || model.isBlank() ? null : model;
        String versionFilter = targetVersion == null || targetVersion.isBlank() ? null : targetVersion;
        return gateRepository.findAll(modelFilter, versionFilter).stream().map(GateView::of).toList();
    }

    /**
     * 发布门禁校验：仅 READY_FOR_RELEASE 的型号+目标版本可创建正式投放。结论不可变。
     */
    public ReleaseGate requireReadyGate(String model, String targetVersion) {
        return gateRepository.findByModelVersion(model, targetVersion)
                .orElseThrow(() -> ApiException.unprocessable("GATE_NOT_READY",
                        "型号 " + model + " 目标版本 " + targetVersion
                                + " 未完成回滚演练，缺少 READY_FOR_RELEASE 门禁，不得创建正式投放"));
    }

    private void validateStart(StartRehearsalRequest request, List<String> normalized) {
        if (request.targetVersion().equals(request.rollbackVersion())) {
            throw ApiException.unprocessable("REHEARSAL_VERSION_INCOMPATIBLE",
                    "回退版本必须与目标版本不同: " + request.targetVersion());
        }
        for (String deviceId : normalized) {
            Device device;
            try {
                device = deviceService.findDevice(deviceId);
            } catch (ApiException e) {
                throw ApiException.unprocessable("REHEARSAL_DEVICE_NOT_FOUND",
                        "演练设备未登记: " + deviceId);
            }
            if (!device.model().equals(request.model())) {
                throw ApiException.unprocessable("REHEARSAL_DEVICE_MODEL_MISMATCH",
                        "设备 " + deviceId + " 型号为 " + device.model() + "，与演练型号 "
                                + request.model() + " 不一致");
            }
            if (!device.currentVersion().equals(request.targetVersion())) {
                throw ApiException.unprocessable("REHEARSAL_VERSION_INCOMPATIBLE",
                        "设备 " + deviceId + " 当前版本 " + device.currentVersion()
                                + " 不等于演练目标版本 " + request.targetVersion()
                                + "，无法演练从目标版本回退");
            }
            if (taskRepository.countByDeviceAcrossReleases(deviceId) > 0) {
                throw ApiException.conflict("DEVICE_HAS_FORMAL_TASK",
                        "设备 " + deviceId + " 已存在正式投放任务，不能作为回滚演练规范设备");
            }
        }
    }

    /**
     * 完整集合最终校验：结果枚举合法、集合与演练规范集合完全一致（不多不少）。
     * 任何不合法都在写入前抛出，保证失败不留半成品。
     */
    private Map<String, ReceiptResult> validateAndParseResults(RehearsalResultRequest request,
                                                               List<RehearsalDevice> canonical) {
        Map<String, ReceiptResult> submitted = new LinkedHashMap<>();
        for (RehearsalResultRequest.DeviceResult dr : request.results()) {
            ReceiptResult parsed;
            try {
                parsed = ReceiptResult.valueOf(dr.result());
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("REHEARSAL_INVALID_RESULT",
                        "设备 " + dr.deviceId() + " 的结果非法: " + dr.result() + "（仅允许 SUCCESS/FAILED）");
            }
            if (submitted.put(dr.deviceId(), parsed) != null) {
                throw ApiException.unprocessable("RESULT_DEVICE_DUPLICATE",
                        "结果集合包含重复设备: " + dr.deviceId());
            }
        }
        List<String> expected = canonical.stream().map(RehearsalDevice::deviceId).toList();
        List<String> missing = expected.stream().filter(id -> !submitted.containsKey(id)).toList();
        List<String> extra = submitted.keySet().stream().filter(id -> !expected.contains(id)).toList();
        if (!missing.isEmpty() || !extra.isEmpty()) {
            throw ApiException.unprocessable("RESULT_SET_MISMATCH",
                    "结果集合必须与演练规范集合完全一致：要求 " + expected.size() + " 台，实际提交 "
                            + submitted.size() + " 台，差额 "
                            + (expected.size() - submitted.size()) + " 台；缺失 " + missing + "，多余 " + extra);
        }
        return submitted;
    }

    /**
     * 门禁按型号+目标版本唯一；同版本再次演练成功时沿用既有不可变结论，不重复授予。
     */
    private void grantGateIfAbsent(RollbackRehearsal rehearsal, int requiredCount, String grantedAtUtc) {
        var existing = gateRepository.findByModelVersion(rehearsal.model(), rehearsal.targetVersion());
        if (existing.isPresent()) {
            return;
        }
        try {
            gateRepository.insert(rehearsal.model(), rehearsal.targetVersion(), rehearsal.id(),
                    requiredCount, requiredCount, grantedAtUtc);
        } catch (DuplicateKeyException e) {
            // 并发下另一事务已授予：结论相同且不可变，沿用即可
        }
    }

    private static List<String> normalizeDeviceIds(List<String> deviceIds) {
        if (deviceIds == null || deviceIds.isEmpty()) {
            throw ApiException.unprocessable("REHEARSAL_EMPTY_DEVICES", "演练设备集合不能为空");
        }
        TreeSet<String> sorted = new TreeSet<>(deviceIds);
        if (sorted.size() != deviceIds.size()) {
            throw ApiException.unprocessable("REHEARSAL_DUPLICATE_DEVICES",
                    "演练设备集合包含重复设备：实际 " + deviceIds.size() + " 台，去重后 " + sorted.size() + " 台");
        }
        return List.copyOf(sorted);
    }

    private static RehearsalView viewOf(RollbackRehearsal rehearsal, List<RehearsalDevice> devices) {
        return RehearsalView.of(rehearsal, devices.stream().map(DeviceResultView::of).toList());
    }
}
