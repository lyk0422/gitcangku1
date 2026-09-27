package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceTrustView;
import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.DeviceTrustRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记与查询。型号与分桶号登记后不可修改。信任集合由证书链登记时授予。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final DeviceTrustRepository deviceTrustRepository;
    private final IdempotencyService idempotency;

    public DeviceService(DeviceRepository deviceRepository, DeviceTrustRepository deviceTrustRepository,
                         IdempotencyService idempotency) {
        this.deviceRepository = deviceRepository;
        this.deviceTrustRepository = deviceTrustRepository;
        this.idempotency = idempotency;
    }

    public DeviceView register(RegisterDeviceRequest request) {
        String fingerprint = String.join("|", "device.register", request.deviceId(), request.model(),
                request.currentVersion(), String.valueOf(request.bucketNo()));
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            try {
                deviceRepository.insert(new Device(request.deviceId(), request.model(),
                        request.currentVersion(), request.bucketNo()));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return new DeviceView(request.deviceId(), request.model(), request.currentVersion(),
                    request.bucketNo());
        }, DeviceView.class);
    }

    public DeviceView get(String deviceId) {
        return DeviceView.of(findDevice(deviceId));
    }

    /**
     * 设备信任集合查询：设备当前信任的全部证书链ID。
     */
    public DeviceTrustView trustOf(String deviceId) {
        findDevice(deviceId);
        return new DeviceTrustView(deviceId, deviceTrustRepository.findChainIdsByDevice(deviceId));
    }

    public Device findDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }
}
