package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ConfigModifyRequest;
import com.example.starter.firmware.api.DeviceConfigView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceConfigRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 设备本地配置修改与查询。整体替换配置项，携带 expectedConfigVersion 乐观校验；
 * 仅当设备无进行中（PENDING）任务时允许修改，与领取、回执共用设备行锁，不使用旧配置。
 */
@Service
public class ConfigService {

    private final DeviceRepository deviceRepository;
    private final DeviceConfigRepository deviceConfigRepository;
    private final TaskRepository taskRepository;
    private final IdempotencyService idempotency;

    public ConfigService(DeviceRepository deviceRepository, DeviceConfigRepository deviceConfigRepository,
                         TaskRepository taskRepository, IdempotencyService idempotency) {
        this.deviceRepository = deviceRepository;
        this.deviceConfigRepository = deviceConfigRepository;
        this.taskRepository = taskRepository;
        this.idempotency = idempotency;
    }

    public DeviceConfigView modify(String deviceId, ConfigModifyRequest request) {
        Map<String, Integer> entries = new TreeMap<>(request.entries());
        String entriesFingerprint = entries.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        String fingerprint = String.join("|", "config.modify", deviceId,
                String.valueOf(request.expectedConfigVersion()), request.operator(), entriesFingerprint);
        return idempotency.execute(request.requestId(), "config.modify", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.configVersion() != request.expectedConfigVersion()) {
                throw ApiException.conflict("CONFIG_VERSION_CONFLICT",
                        "expectedConfigVersion 与当前配置版本不一致: " + device.configVersion());
            }
            if (taskRepository.countPendingByDevice(deviceId) > 0) {
                throw ApiException.conflict("DEVICE_TASK_IN_PROGRESS",
                        "设备存在进行中任务，禁止本地配置修改: " + deviceId);
            }
            deviceConfigRepository.replaceAll(deviceId, entries);
            deviceRepository.updateConfigVersion(deviceId, device.configVersion() + 1);
            return new DeviceConfigView(deviceId, device.configVersion() + 1, entries);
        }, DeviceConfigView.class);
    }

    public DeviceConfigView get(String deviceId) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
        return new DeviceConfigView(deviceId, device.configVersion(),
                deviceConfigRepository.findByDevice(deviceId));
    }
}
