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
 * 设备登记、查询与本地维护更新。型号与分桶号登记后不可修改。
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
        String fingerprint = idempotency.structuredFingerprint("device.register", request.deviceId(),
                request.model(), request.currentVersion(), request.currentDigest(), request.bucketNo());
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            try {
                deviceRepository.insert(new Device(request.deviceId(), request.model(),
                        request.currentVersion(), request.currentDigest(), 1, request.bucketNo()));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return DeviceView.of(deviceRepository.findById(request.deviceId()).orElseThrow());
        }, DeviceView.class);
    }

    /**
     * 设备本地维护更新：更新当前版本与镜像摘要并使镜像代次加一，型号与灰度桶不可修改。
     * 持有设备行锁，与增量拉取、安装回执串行；expectedImageGeneration 不符时 409。
     */
    public DeviceView maintenance(String deviceId, MaintenanceRequest request) {
        String fingerprint = idempotency.structuredFingerprint("device.maintenance", request.operator(),
                deviceId, request.newVersion(), request.newDigest(), request.expectedImageGeneration());
        return idempotency.execute(request.requestId(), "device.maintenance", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.imageGeneration() != request.expectedImageGeneration()) {
                throw ApiException.conflict("GENERATION_CONFLICT",
                        "expectedImageGeneration 与当前镜像代次不一致: " + device.imageGeneration());
            }
            deviceRepository.maintenanceUpdate(deviceId, request.newVersion(), request.newDigest());
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