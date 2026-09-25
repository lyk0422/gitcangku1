package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.HardwareModelRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记与查询。产品型号、硬件型号与分桶号登记后不可修改。
 * 登记设备时自动确保其硬件型号进入已知硬件型号目录，矩阵才能引用该型号。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final HardwareModelRepository hardwareModelRepository;
    private final IdempotencyService idempotency;

    public DeviceService(DeviceRepository deviceRepository, HardwareModelRepository hardwareModelRepository,
                         IdempotencyService idempotency) {
        this.deviceRepository = deviceRepository;
        this.hardwareModelRepository = hardwareModelRepository;
        this.idempotency = idempotency;
    }

    public DeviceView register(RegisterDeviceRequest request) {
        String fingerprint = String.join("|", "device.register", request.deviceId(), request.model(),
                request.hardwareModel(), request.currentVersion(), String.valueOf(request.bucketNo()));
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            try {
                hardwareModelRepository.register(request.hardwareModel());
                deviceRepository.insert(new Device(request.deviceId(), request.model(),
                        request.hardwareModel(), request.currentVersion(), request.bucketNo()));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return new DeviceView(request.deviceId(), request.model(), request.hardwareModel(),
                    request.currentVersion(), request.bucketNo());
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
