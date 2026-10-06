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
 * 设备登记、查询与安全维护。型号与分桶号登记后不可修改；安全底线只增不减，
 * 安全档案版本从1开始，维护与首次成功安装加一。
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
        int securityFloor = request.effectiveSecurityFloor();
        String fingerprint = Fingerprints.of("device.register", request.deviceId(), request.model(),
                request.currentVersion(), request.bucketNo(), securityFloor);
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            try {
                deviceRepository.insert(new Device(request.deviceId(), request.model(),
                        request.currentVersion(), request.bucketNo(), securityFloor, 1));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return new DeviceView(request.deviceId(), request.model(), request.currentVersion(),
                    request.bucketNo(), securityFloor, 1);
        }, DeviceView.class);
    }

    /**
     * 安全维护：提升设备安全底线。expectedDeviceVersion 必须等于当前安全档案版本，成功加一；
     * 新底线低于当前底线返回 400。维护只改安全档案，不改固件版本与监控样本。
     */
    public DeviceView maintenance(String deviceId, MaintenanceRequest request) {
        String operator = request.operator() == null ? "anonymous" : request.operator();
        String fingerprint = Fingerprints.of("device.maintenance", operator, deviceId,
                request.expectedDeviceVersion(), request.securityFloor());
        return idempotency.execute(request.requestId(), "device.maintenance", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.securityVersion() != request.expectedDeviceVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedDeviceVersion 与当前安全档案版本不一致: " + device.securityVersion());
            }
            if (request.securityFloor() < device.securityFloor()) {
                throw ApiException.badRequest("FLOOR_DECREASE",
                        "安全底线只增不减，当前: " + device.securityFloor());
            }
            deviceRepository.raiseFloor(deviceId, request.expectedDeviceVersion(), request.securityFloor());
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
