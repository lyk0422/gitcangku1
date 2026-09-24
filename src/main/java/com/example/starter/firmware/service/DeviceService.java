package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.DeviceWindowView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UpdateWindowRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.MaintenanceWindow;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记、维护窗口修改与查询。型号与分桶号登记后不可修改；
 * 窗口修改只影响后续拉取判定，不改写已下发任务、已提交回执和历史顺延记录。
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
        if (!request.windowAbsent() && !request.windowComplete()) {
            throw ApiException.badRequest("WINDOW_INCOMPLETE",
                    "维护窗口须同时提供 utcOffsetMinutes、windowStartMinute、windowEndMinute");
        }
        if (request.windowComplete() && !MaintenanceWindow.isValid(request.utcOffsetMinutes(),
                request.windowStartMinute(), request.windowEndMinute())) {
            throw ApiException.badRequest("WINDOW_INVALID", "维护窗口起止分钟必须不同且在0~1439之间");
        }
        String fingerprint = String.join("|", "device.register", request.deviceId(), request.model(),
                request.currentVersion(), String.valueOf(request.bucketNo()),
                String.valueOf(request.utcOffsetMinutes()), String.valueOf(request.windowStartMinute()),
                String.valueOf(request.windowEndMinute()));
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            Device device = new Device(request.deviceId(), request.model(), request.currentVersion(),
                    request.bucketNo(), 1, request.utcOffsetMinutes(), request.windowStartMinute(),
                    request.windowEndMinute());
            try {
                deviceRepository.insert(device);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return DeviceView.of(device);
        }, DeviceView.class);
    }

    /**
     * 修改设备维护窗口：携带设备配置版本 expectedVersion，冲突 409；成功后版本加一。
     */
    public DeviceWindowView updateWindow(String deviceId, UpdateWindowRequest request) {
        if (!MaintenanceWindow.isValid(request.utcOffsetMinutes(), request.windowStartMinute(),
                request.windowEndMinute())) {
            throw ApiException.badRequest("WINDOW_INVALID", "维护窗口起止分钟必须不同且在0~1439之间");
        }
        String fingerprint = String.join("|", "device.updateWindow", deviceId,
                String.valueOf(request.expectedVersion()), String.valueOf(request.utcOffsetMinutes()),
                String.valueOf(request.windowStartMinute()), String.valueOf(request.windowEndMinute()));
        return idempotency.execute(request.requestId(), "device.updateWindow", fingerprint, () -> {
            Device device = deviceRepository.findByIdForUpdate(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            if (device.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + device.version());
            }
            deviceRepository.updateWindow(deviceId, request.expectedVersion(), request.utcOffsetMinutes(),
                    request.windowStartMinute(), request.windowEndMinute());
            return DeviceWindowView.of(findDevice(deviceId));
        }, DeviceWindowView.class);
    }

    public DeviceView get(String deviceId) {
        return DeviceView.of(findDevice(deviceId));
    }

    public DeviceWindowView getWindow(String deviceId) {
        return DeviceWindowView.of(findDevice(deviceId));
    }

    public Device findDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }
}
