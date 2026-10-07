package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UpdateDeviceConfigRequest;
import com.example.starter.firmware.domain.ConfigSnapshots;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceConfigRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;

/**
 * 设备登记、查询与本地配置修改。型号与分桶号登记后不可修改。
 * 本地配置修改携带 expectedConfigVersion，仅无进行中（PENDING）任务时允许；
 * 与拉取、回执共用设备行锁，保证不使用领取后变更的旧配置。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final DeviceConfigRepository deviceConfigRepository;
    private final TaskRepository taskRepository;
    private final IdempotencyService idempotency;

    public DeviceService(DeviceRepository deviceRepository, DeviceConfigRepository deviceConfigRepository,
                         TaskRepository taskRepository, IdempotencyService idempotency) {
        this.deviceRepository = deviceRepository;
        this.deviceConfigRepository = deviceConfigRepository;
        this.taskRepository = taskRepository;
        this.idempotency = idempotency;
    }

    public DeviceView register(RegisterDeviceRequest request) {
        Map<String, Integer> config = new TreeMap<>(request.effectiveConfig());
        String fingerprint = String.join("|", "device.register", request.deviceId(), request.model(),
                request.currentVersion(), String.valueOf(request.bucketNo()), ConfigSnapshots.toJson(config));
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            try {
                deviceRepository.insert(new Device(request.deviceId(), request.model(),
                        request.currentVersion(), request.bucketNo(), 1));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            deviceConfigRepository.upsert(request.deviceId(), config);
            return new DeviceView(request.deviceId(), request.model(), request.currentVersion(),
                    request.bucketNo(), 1, config);
        }, DeviceView.class);
    }

    /**
     * 本地配置修改：逐键设置并推进配置版本。仅无进行中任务时允许，否则 409；
     * expectedConfigVersion 与当前不一致 409；合并后键数超过上限 422。
     */
    public DeviceView updateConfig(String deviceId, UpdateDeviceConfigRequest request) {
        Map<String, Integer> entries = new TreeMap<>(request.entries());
        String fingerprint = String.join("|", "device.config.update", deviceId, request.operator(),
                String.valueOf(request.expectedConfigVersion()), ConfigSnapshots.toJson(entries));
        return idempotency.execute(request.requestId(), "device.config.update", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.configVersion() != request.expectedConfigVersion()) {
                throw ApiException.conflict("CONFIG_VERSION_CONFLICT",
                        "expectedConfigVersion 与当前配置版本不一致: " + device.configVersion());
            }
            if (taskRepository.countPendingByDevice(deviceId) > 0) {
                throw ApiException.conflict("TASK_IN_PROGRESS", "设备存在进行中投放任务，禁止本地配置修改");
            }
            Map<String, Integer> merged = new TreeMap<>(deviceConfigRepository.findByDevice(deviceId));
            merged.putAll(entries);
            if (merged.size() > ConfigMigrationService.MAX_KEYS) {
                throw ApiException.unprocessable("CONFIG_KEY_LIMIT",
                        "配置键数超过上限 " + ConfigMigrationService.MAX_KEYS + ": " + merged.size());
            }
            deviceConfigRepository.upsert(deviceId, entries);
            deviceRepository.incrementConfigVersion(deviceId);
            Device updated = deviceRepository.findById(deviceId).orElseThrow();
            return DeviceView.of(updated, merged);
        }, DeviceView.class);
    }

    public DeviceView get(String deviceId) {
        return DeviceView.of(findDevice(deviceId), deviceConfigRepository.findByDevice(deviceId));
    }

    public Device findDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }
}
