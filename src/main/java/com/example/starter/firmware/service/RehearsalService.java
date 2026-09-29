package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CompleteRehearsalRequest;
import com.example.starter.firmware.api.GateDiagnosticsResponse;
import com.example.starter.firmware.api.GateView;
import com.example.starter.firmware.api.RehearsalDetailResponse;
import com.example.starter.firmware.api.RehearsalDeviceView;
import com.example.starter.firmware.api.RehearsalHistoryResponse;
import com.example.starter.firmware.api.RehearsalView;
import com.example.starter.firmware.api.StartRehearsalRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.RehearsalDevice;
import com.example.starter.firmware.domain.RehearsalStatus;
import com.example.starter.firmware.domain.ReleaseGate;
import com.example.starter.firmware.domain.RollbackRehearsal;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.GateRepository;
import com.example.starter.firmware.repo.RehearsalRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 固件回滚演练：开始演练（校验规范设备集合）、批量提交结果（先校验完整最终状态，单事务提交）、
 * 全成功且结束不早于开始时在同一事务授予 READY_FOR_RELEASE 门禁。
 * 所有时刻取自统一 UTC 时钟，区间按左闭右开解释；演练、设备结果与门禁只增不改。
 */
@Service
public class RehearsalService {

    private final RehearsalRepository rehearsalRepository;
    private final GateRepository gateRepository;
    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RehearsalService(RehearsalRepository rehearsalRepository, GateRepository gateRepository,
                            DeviceRepository deviceRepository, IdempotencyService idempotency,
                            ObjectMapper objectMapper, Clock clock) {
        this.rehearsalRepository = rehearsalRepository;
        this.gateRepository = gateRepository;
        this.deviceRepository = deviceRepository;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 开始演练：校验集合非空不重复、设备存在/型号一致/当前处于目标版本、版本不相容、
     * 目标版本已有正式任务、同版本组合已有进行中演练；通过后在一个事务内落演练与规范设备行。
     */
    public RehearsalView start(StartRehearsalRequest request) {
        List<String> devices = normalizeDeviceIds(request.deviceIds());
        String fingerprint = canonicalFingerprint("rehearsal.start",
                List.of(request.operator(), request.model(), request.targetVersion(),
                        request.rollbackVersion(), canonicalJson(devices)));
        return idempotency.execute(request.requestId(), "rehearsal.start", fingerprint, () -> {
            validateVersionPair(request.targetVersion(), request.rollbackVersion());
            validateStartDevices(request.model(), request.targetVersion(), devices);
            if (rehearsalRepository.countFormalTasks(request.model(), request.targetVersion(), devices) > 0) {
                throw ApiException.conflict("FORMAL_TASK_EXISTS",
                        "目标版本已存在正式投放任务，不能再开始演练: " + request.targetVersion());
            }
            String startedAt = Instant.now(clock).toString();
            String activeKey = activeKey(request.model(), request.targetVersion(), request.rollbackVersion());
            long rehearsalId;
            try {
                rehearsalId = rehearsalRepository.insert(request.operator(), request.model(),
                        request.targetVersion(), request.rollbackVersion(), devices.size(), startedAt, activeKey);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("REHEARSAL_IN_PROGRESS",
                        "同型号与版本组合已存在进行中的回滚演练");
            }
            for (int i = 0; i < devices.size(); i++) {
                rehearsalRepository.insertDevice(rehearsalId, devices.get(i), i);
            }
            return viewOf(rehearsalRepository.findById(rehearsalId).orElseThrow());
        }, RehearsalView.class);
    }

    /**
     * 提交结果：先锁定演练与设备行并校验完整集合（数量、缺漏、重复、失败原因、设备当前状态），
     * 全部通过后在同一事务批量落每台结果、终结演练；全成功且结束不早于开始时真实回退设备版本并授予门禁。
     * 任一校验失败整体回滚：无设备结果、无终态、无门禁（不留半成品）。
     */
    public RehearsalDetailResponse complete(long rehearsalId, CompleteRehearsalRequest request) {
        List<Map<String, String>> canonicalResults = canonicalResults(request.results());
        String fingerprint = canonicalFingerprint("rehearsal.complete",
                List.of(String.valueOf(rehearsalId), request.operator(), canonicalJson(canonicalResults)));
        return idempotency.execute(request.requestId(), "rehearsal.complete", fingerprint, () -> {
            RollbackRehearsal rehearsal = rehearsalRepository.findByIdForUpdate(rehearsalId)
                    .orElseThrow(() -> ApiException.notFound("REHEARSAL_NOT_FOUND",
                            "回滚演练不存在: " + rehearsalId));
            if (rehearsal.status() != RehearsalStatus.RUNNING) {
                throw ApiException.conflict("REHEARSAL_FINALIZED",
                        "演练已终结为 " + rehearsal.status() + "，结果不可改写");
            }
            List<RehearsalDevice> planned = rehearsalRepository.findDevicesForUpdate(rehearsalId);
            validateResultSet(rehearsal, planned, request.results());

            Map<String, CompleteRehearsalRequest.DeviceResultInput> submitted = new LinkedHashMap<>();
            for (CompleteRehearsalRequest.DeviceResultInput input : request.results()) {
                submitted.put(input.deviceId(), input);
            }

            int success = 0;
            int failed = 0;
            // 先批量落设备结果，再根据完整最终状态决定演练结论与门禁，失败与门禁写入同一事务。
            for (RehearsalDevice device : planned) {
                CompleteRehearsalRequest.DeviceResultInput input = submitted.get(device.deviceId());
                String reason = input.result() == ReceiptResult.FAILED
                        ? normalizeReason(input.failureReason()) : null;
                rehearsalRepository.completeDevice(device.id(), input.result(), reason);
                if (input.result() == ReceiptResult.SUCCESS) {
                    success++;
                } else {
                    failed++;
                }
            }

            String finishedAt = Instant.now(clock).toString();
            boolean endedNotBeforeStart = !Instant.parse(finishedAt).isBefore(Instant.parse(rehearsal.startedAtUtc()));
            boolean allSucceeded = failed == 0;
            String deniedReason = null;
            RehearsalStatus finalStatus;
            if (!allSucceeded) {
                finalStatus = RehearsalStatus.FAILED;
                deniedReason = "DEVICE_FAILED";
            } else if (!endedNotBeforeStart) {
                finalStatus = RehearsalStatus.FAILED;
                deniedReason = "FINISHED_BEFORE_START";
            } else {
                finalStatus = RehearsalStatus.PASSED;
            }
            rehearsalRepository.complete(rehearsalId, finalStatus, success, failed, finishedAt, deniedReason);

            if (finalStatus == RehearsalStatus.PASSED) {
                rollbackDevices(rehearsal, planned);
                try {
                    gateRepository.insert(rehearsal.model(), rehearsal.targetVersion(),
                            rehearsal.rollbackVersion(), rehearsalId, request.operator(), finishedAt);
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("GATE_EXISTS", "该版本组合已存在门禁结论");
                }
            }
            return detailOf(rehearsalRepository.findById(rehearsalId).orElseThrow());
        }, RehearsalDetailResponse.class);
    }

    public RehearsalDetailResponse detail(long rehearsalId) {
        return detailOf(findRehearsal(rehearsalId));
    }

    public RehearsalHistoryResponse history(String model, String targetVersion, String rollbackVersion) {
        if (model == null || model.isBlank()) {
            throw ApiException.badRequest("MODEL_REQUIRED", "历史查询必须指定型号");
        }
        List<RehearsalView> views = rehearsalRepository
                .findByFilters(model, blankToNull(targetVersion), blankToNull(rollbackVersion)).stream()
                .map(this::viewOf).toList();
        return new RehearsalHistoryResponse(model, blankToNull(targetVersion), blankToNull(rollbackVersion),
                views.size(), views);
    }

    public GateDiagnosticsResponse gates(String model, String targetVersion, String rollbackVersion) {
        if (model == null || model.isBlank()) {
            throw ApiException.badRequest("MODEL_REQUIRED", "门禁诊断必须指定型号");
        }
        List<GateView> views = gateRepository
                .findByFilters(model, blankToNull(targetVersion), blankToNull(rollbackVersion)).stream()
                .map(GateView::of).toList();
        return new GateDiagnosticsResponse(model, blankToNull(targetVersion), blankToNull(rollbackVersion),
                views.size(), views);
    }

    public RollbackRehearsal findRehearsal(long rehearsalId) {
        return rehearsalRepository.findById(rehearsalId)
                .orElseThrow(() -> ApiException.notFound("REHEARSAL_NOT_FOUND",
                        "回滚演练不存在: " + rehearsalId));
    }

    /**
     * 正式投放创建前门禁校验：同一 型号|来源版本|目标版本 必须存在 READY_FOR_RELEASE 门禁。
     */
    public void requireReleaseGate(String model, String fromVersion, String toVersion) {
        if (gateRepository.findByVersions(model, toVersion, fromVersion).isEmpty()) {
            throw ApiException.conflict("GATE_NOT_READY",
                    "目标版本未取得 READY_FOR_RELEASE 门禁，不能创建正式投放: model=" + model
                            + ", from=" + fromVersion + ", to=" + toVersion);
        }
    }

    private void validateVersionPair(String targetVersion, String rollbackVersion) {
        if (targetVersion.equals(rollbackVersion)) {
            throw ApiException.unprocessable("VERSION_INCOMPATIBLE",
                    "回退版本必须与目标版本不同");
        }
    }

    private void validateStartDevices(String model, String targetVersion, List<String> deviceIds) {
        for (String deviceId : deviceIds) {
            Device device = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (!device.model().equals(model)) {
                throw ApiException.unprocessable("MODEL_MISMATCH",
                        "设备 " + deviceId + " 型号为 " + device.model() + "，与演练型号 " + model + " 不一致");
            }
            if (!device.currentVersion().equals(targetVersion)) {
                throw ApiException.unprocessable("VERSION_INCOMPATIBLE",
                        "设备 " + deviceId + " 当前版本为 " + device.currentVersion()
                                + "，开始演练时必须处于目标版本 " + targetVersion);
            }
        }
    }

    /**
     * 完整最终状态校验：提交集合与规范集合必须完全相等；FAILED 必须带原因；
     * SUCCESS 的设备必须仍存在、型号一致且当前仍在目标版本（设备状态已变化则本批结果不被接受）。
     */
    private void validateResultSet(RollbackRehearsal rehearsal, List<RehearsalDevice> planned,
                                   List<CompleteRehearsalRequest.DeviceResultInput> submitted) {
        if (submitted.size() != rehearsal.deviceCount()) {
            throw ApiException.unprocessable("RESULT_SET_MISMATCH",
                    "结果设备数 " + submitted.size() + " 与规范集合要求数 " + rehearsal.deviceCount()
                            + " 不一致，差额 " + (rehearsal.deviceCount() - submitted.size()));
        }
        TreeSet<String> unique = new TreeSet<>();
        for (CompleteRehearsalRequest.DeviceResultInput input : submitted) {
            if (!unique.add(input.deviceId())) {
                throw ApiException.unprocessable("DUPLICATE_DEVICE",
                        "结果集合包含重复设备: " + input.deviceId());
            }
            if (input.result() == ReceiptResult.FAILED && normalizeReason(input.failureReason()) == null) {
                throw ApiException.unprocessable("MISSING_FAILURE_REASON",
                        "设备 " + input.deviceId() + " 回退失败必须提供 failureReason");
            }
            if (input.failureReason() != null && input.failureReason().length() > 256) {
                throw ApiException.unprocessable("FAILURE_REASON_TOO_LONG",
                        "设备 " + input.deviceId() + " 的 failureReason 长度不能超过256");
            }
        }
        for (RehearsalDevice plannedDevice : planned) {
            if (!unique.contains(plannedDevice.deviceId())) {
                throw ApiException.unprocessable("RESULT_SET_MISMATCH",
                        "缺少规范设备的结果: " + plannedDevice.deviceId());
            }
        }
        Map<String, CompleteRehearsalRequest.DeviceResultInput> byId = new LinkedHashMap<>();
        for (CompleteRehearsalRequest.DeviceResultInput input : submitted) {
            byId.put(input.deviceId(), input);
        }
        for (RehearsalDevice plannedDevice : planned) {
            CompleteRehearsalRequest.DeviceResultInput input = byId.get(plannedDevice.deviceId());
            if (input.result() != ReceiptResult.SUCCESS) {
                continue;
            }
            Device device = deviceRepository.findById(plannedDevice.deviceId())
                    .orElseThrow(() -> ApiException.unprocessable("DEVICE_NOT_FOUND",
                            "设备不存在: " + plannedDevice.deviceId()));
            if (!device.model().equals(rehearsal.model())
                    || !device.currentVersion().equals(rehearsal.targetVersion())) {
                throw ApiException.unprocessable("VERSION_INCOMPATIBLE",
                        "设备 " + device.deviceId() + " 当前状态与演练证据不一致（型号 " + device.model()
                                + "，版本 " + device.currentVersion() + "），整批结果不予受理");
            }
        }
    }

    /**
     * PASSED 演练的成功设备真实回退到回退版本（正式投放的来源版本）。
     */
    private void rollbackDevices(RollbackRehearsal rehearsal, List<RehearsalDevice> planned) {
        for (RehearsalDevice device : planned) {
            deviceRepository.updateCurrentVersion(device.deviceId(), rehearsal.rollbackVersion());
        }
    }

    private RehearsalView viewOf(RollbackRehearsal rehearsal) {
        ReleaseGate gate = gateRepository.findByRehearsal(rehearsal.id()).orElse(null);
        return RehearsalView.of(rehearsal, gate);
    }

    private RehearsalDetailResponse detailOf(RollbackRehearsal rehearsal) {
        List<RehearsalDeviceView> devices = rehearsalRepository.findDevices(rehearsal.id()).stream()
                .map(RehearsalDeviceView::of).toList();
        return new RehearsalDetailResponse(viewOf(rehearsal), devices);
    }

    /**
     * 规范化设备集合：去重并按 device_id 升序；空集合/重复由调用方据此拒绝。
     */
    private List<String> normalizeDeviceIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.unprocessable("EMPTY_DEVICE_SET", "规范设备集合不能为空");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String deviceId : raw) {
            if (deviceId == null || deviceId.isBlank()) {
                throw ApiException.unprocessable("EMPTY_DEVICE_SET", "设备ID不能为空");
            }
            if (deviceId.length() > 64) {
                throw ApiException.unprocessable("DEVICE_ID_TOO_LONG", "设备ID长度不能超过64: " + deviceId);
            }
            if (!sorted.add(deviceId)) {
                throw ApiException.unprocessable("DUPLICATE_DEVICE", "设备集合包含重复设备: " + deviceId);
            }
        }
        return new ArrayList<>(sorted);
    }

    /**
     * 规范化结果集合用于幂等指纹：按 device_id 升序，SUCCESS 的失败原因归一为 null。
     * 基础字段（设备ID、结果枚举）缺失或集合为空/重复统一返回 422。
     */
    private List<Map<String, String>> canonicalResults(List<CompleteRehearsalRequest.DeviceResultInput> raw) {
        if (raw == null || raw.isEmpty()) {
            throw ApiException.unprocessable("EMPTY_DEVICE_SET", "结果集合不能为空");
        }
        TreeSet<String> unique = new TreeSet<>();
        List<Map<String, String>> rows = new ArrayList<>();
        List<CompleteRehearsalRequest.DeviceResultInput> sorted = new ArrayList<>(raw);
        sorted.sort(java.util.Comparator.comparing(input -> {
            if (input == null || input.deviceId() == null || input.deviceId().isBlank()) {
                throw ApiException.unprocessable("EMPTY_DEVICE_SET", "结果中设备ID不能为空");
            }
            return input.deviceId();
        }));
        for (CompleteRehearsalRequest.DeviceResultInput input : sorted) {
            if (input.result() == null) {
                throw ApiException.unprocessable("RESULT_REQUIRED",
                        "设备 " + input.deviceId() + " 的回退结果不能为空");
            }
            if (input.deviceId().length() > 64) {
                throw ApiException.unprocessable("DEVICE_ID_TOO_LONG",
                        "设备ID长度不能超过64: " + input.deviceId());
            }
            if (!unique.add(input.deviceId())) {
                throw ApiException.unprocessable("DUPLICATE_DEVICE",
                        "结果集合包含重复设备: " + input.deviceId());
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("deviceId", input.deviceId());
            row.put("result", input.result().name());
            row.put("failureReason", input.result() == ReceiptResult.FAILED
                    ? normalizeReason(input.failureReason()) : null);
            rows.add(row);
        }
        return rows;
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason;
    }

    private String activeKey(String model, String targetVersion, String rollbackVersion) {
        return model + "|" + targetVersion + "|" + rollbackVersion;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 幂等指纹：操作类型 + 操作者 + 规范化对象集合（JSON 序列化，集合元素不依赖未转义分隔符拼接）
     * + 所有影响结果的字段。
     */
    private String canonicalFingerprint(String api, List<Object> parts) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("api", api);
        root.put("parts", parts);
        return canonicalJson(root);
    }

    private String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }
}
