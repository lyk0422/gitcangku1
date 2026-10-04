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
 * 维护更新与拉取、安装回执通过设备行锁串行，每次成功维护使镜像代次加一。
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
        String digest = request.currentDigest() == null
                ? Digests.synthesize(request.deviceId(), request.currentVersion())
                : Digests.normalize(request.currentDigest(), "currentDigest");
        String fingerprint = idempotency.fingerprint("device.register", request.deviceId(), request.model(),
                request.currentVersion(), request.bucketNo(), digest);
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            try {
                deviceRepository.insert(new Device(request.deviceId(), request.model(),
                        request.currentVersion(), digest, 1, request.bucketNo()));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return DeviceView.of(findDevice(request.deviceId()));
        }, DeviceView.class);
    }

    /**
     * 设备本地维护更新：仅更新当前版本与当前摘要并使镜像代次加一，不能改型号和灰度桶。
     * 期望代次与设备当前代次不符返回 409；与拉取、安装回执通过设备行锁串行。
     */
    public DeviceView maintain(String deviceId, MaintenanceRequest request) {
        String newDigest = Digests.normalize(request.newDigest(), "newDigest");
        String fingerprint = idempotency.fingerprint("device.maintain", request.operator(), deviceId,
                request.newVersion(), newDigest, request.expectedImageGeneration());
        return idempotency.execute(request.requestId(), "device.maintain", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.imageGeneration() != request.expectedImageGeneration()) {
                throw ApiException.conflict("GENERATION_CONFLICT",
                        "expectedImageGeneration 与当前镜像代次不一致: " + device.imageGeneration());
            }
            deviceRepository.applyMaintenance(deviceId, request.newVersion(), newDigest);
            return DeviceView.of(findDevice(deviceId));
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