package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.RecoverDeviceRequest;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记、查询与人工恢复。型号与分桶号登记后不可修改。
 * 人工恢复仅对停机（不健康）设备生效：校验期望健康版本，成功后健康版本加一并解除停机。
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
                    request.bucketNo(), 1, false);
            try {
                deviceRepository.insert(device);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return DeviceView.of(device);
        }, DeviceView.class);
    }

    /**
     * 人工恢复：设备行锁内校验停机状态与期望健康版本，成功则健康版本加一并解除停机；
     * 失败设备不会自动恢复，只能经本入口或 SUCCESS 回执恢复健康。
     */
    public DeviceView recover(String deviceId, RecoverDeviceRequest request) {
        String fingerprint = String.join("|", "device.recover", request.operator(), deviceId,
                String.valueOf(request.expectedHealthVersion()));
        return idempotency.execute(request.requestId(), "device.recover", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (!device.down()) {
                throw ApiException.conflict("DEVICE_NOT_DOWN", "设备当前健康，无需人工恢复: " + deviceId);
            }
            if (device.healthVersion() != request.expectedHealthVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedHealthVersion 与当前健康版本不一致: " + device.healthVersion());
            }
            deviceRepository.recoverIfDown(deviceId, request.expectedHealthVersion());
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
