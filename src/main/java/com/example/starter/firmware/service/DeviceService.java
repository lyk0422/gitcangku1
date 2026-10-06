package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.MaintenanceRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记、查询与维护。型号与分桶号登记后不可修改。
 * 维护：提交 expectedDeviceVersion 乐观校验档案版本，通过后将启动计数维护到指定值
 * （不可递减）并推进档案版本；与启动挑战回执共用设备行锁串行裁决。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotency;

    public DeviceService(DeviceRepository deviceRepository, IdempotencyService idempotency) {
        this.deviceRepository = deviceRepository;
        this.idempotency = idempotency;
    }

    public DeviceView register(RegisterDeviceRequest request) {
        String fingerprint = String.join("|", "device.register", request.deviceId(), request.model(),
                request.currentVersion(), String.valueOf(request.bucketNo()));
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            Device device = new Device(request.deviceId(), request.model(), request.currentVersion(),
                    request.bucketNo(), 0L, 1);
            try {
                deviceRepository.insert(device);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return DeviceView.of(device);
        }, DeviceView.class);
    }

    /**
     * 维护启动计数：expectedDeviceVersion 与当前档案版本不一致 409；bootCount 低于当前计数 409。
     * 成功后档案版本加一；同键同参重放首次响应，不重复推进版本。
     */
    public DeviceView maintain(String deviceId, MaintenanceRequest request) {
        String fingerprint = String.join("|", "device.maintenance", deviceId, request.operator(),
                String.valueOf(request.expectedDeviceVersion()), String.valueOf(request.bootCount()));
        return idempotency.execute(request.requestId(), "device.maintenance", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.deviceVersion() != request.expectedDeviceVersion()) {
                throw ApiException.conflict("DEVICE_VERSION_CONFLICT",
                        "expectedDeviceVersion 与当前档案版本不一致: " + device.deviceVersion());
            }
            if (request.bootCount() < device.bootCount()) {
                throw ApiException.conflict("BOOT_COUNT_REGRESS",
                        "启动计数不可递减，当前: " + device.bootCount());
            }
            deviceRepository.maintain(deviceId, request.bootCount());
            return DeviceView.of(deviceRepository.findById(deviceId).orElseThrow());
        }, DeviceView.class);
    }

    public DeviceView get(String deviceId) {
        return DeviceView.of(findDevice(deviceId));
    }

    public Device findDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }
}
