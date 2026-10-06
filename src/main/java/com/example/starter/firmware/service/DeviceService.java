package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.RecoverDeviceRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.HealthStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记、查询与人工恢复。型号与分桶号登记后不可修改。
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
            Device device = Device.registered(request.deviceId(), request.model(),
                    request.currentVersion(), request.bucketNo());
            try {
                deviceRepository.insert(device);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return DeviceView.of(device);
        }, DeviceView.class);
    }

    /**
     * 人工恢复：仅 UNHEALTHY 可恢复为 HEALTHY；expectedHealthVersion 必须等于当前健康版本，成功加一。
     * 与波次领取并发时按设备行锁提交顺序决定结果。
     */
    public DeviceView recover(String deviceId, RecoverDeviceRequest request) {
        String fingerprint = idempotency.fingerprint("device.recover", deviceId, request.operator(),
                request.expectedHealthVersion());
        return idempotency.execute(request.requestId(), "device.recover", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.healthStatus() != HealthStatus.UNHEALTHY) {
                throw ApiException.conflict("DEVICE_NOT_UNHEALTHY",
                        "设备健康状态为 " + device.healthStatus() + "，仅 UNHEALTHY 可人工恢复");
            }
            if (device.healthVersion() != request.expectedHealthVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedHealthVersion 与当前健康版本不一致: " + device.healthVersion());
            }
            deviceRepository.recoverIfUnhealthy(deviceId, request.expectedHealthVersion());
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
