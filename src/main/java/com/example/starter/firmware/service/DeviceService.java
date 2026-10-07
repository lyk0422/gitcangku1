package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BootMaintenanceRequest;
import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记、查询与启动计数维护。型号与分桶号登记后不可修改；
 * 启动计数不可递减，档案版本从1开始、每次计数维护成功加一。
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
            try {
                deviceRepository.insert(new Device(request.deviceId(), request.model(),
                        request.currentVersion(), request.bucketNo(), 0, 1));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return new DeviceView(request.deviceId(), request.model(), request.currentVersion(),
                    request.bucketNo(), 0, 1);
        }, DeviceView.class);
    }

    /**
     * 启动计数维护：持设备行锁串行裁决，expectedDeviceVersion 匹配且计数不递减才生效，
     * 成功后档案版本加一。与领取、回执、取消按行锁提交顺序形成一致结果。
     */
    public DeviceView maintainBootCount(String deviceId, BootMaintenanceRequest request) {
        long bootCount = BootCountParser.parseRequired(request.bootCount());
        String fingerprint = String.join("|", "device.bootMaintain", deviceId,
                String.valueOf(bootCount), String.valueOf(request.expectedDeviceVersion()),
                request.operator());
        return idempotency.execute(request.requestId(), "device.bootMaintain", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.profileVersion() != request.expectedDeviceVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedDeviceVersion 与当前档案版本不一致: " + device.profileVersion());
            }
            if (bootCount < device.bootCount()) {
                throw ApiException.conflict("BOOT_COUNT_REGRESS",
                        "启动计数不可递减，当前: " + device.bootCount());
            }
            deviceRepository.maintainBootCount(deviceId, bootCount, request.expectedDeviceVersion());
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
