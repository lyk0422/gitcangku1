package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.api.UpdateDeviceWindowRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记与查询，以及 UTC 偏移与每日维护窗口修订。型号与分桶号登记后不可修改。
 * 窗口修订走设备乐观锁（expectedVersion/device_version），只影响后续拉取判定，
 * 不改写已下发任务、已提交回执与历史顺延记录。
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
        if (request.hasWindowBounds()
                && (request.windowStartMinute() == null || request.windowEndMinute() == null)) {
            throw ApiException.badRequest("WINDOW_BOUNDS_INCOMPLETE",
                    "维护窗口开始分钟与结束分钟必须同时提供");
        }
        if (request.hasWindowBounds()
                && request.windowStartMinute().equals(request.windowEndMinute())) {
            // 显式登记窗口时起止必须不同；全天窗口仅允许旧客户端缺省（不传窗口字段）
            throw ApiException.badRequest("WINDOW_START_END_SAME",
                    "维护窗口开始分钟与结束分钟不能相同（起大于止表示跨零点）");
        }
        int offset = request.effectiveUtcOffsetMinutes();
        int windowStart = request.effectiveWindowStartMinute();
        int windowEnd = request.effectiveWindowEndMinute();
        String fingerprint = String.join("|", "device.register", request.deviceId(), request.model(),
                request.currentVersion(), String.valueOf(request.bucketNo()),
                String.valueOf(offset), String.valueOf(windowStart), String.valueOf(windowEnd));
        return idempotency.execute(request.requestId(), "device.register", fingerprint, () -> {
            try {
                deviceRepository.insert(new Device(request.deviceId(), request.model(),
                        request.currentVersion(), request.bucketNo(), offset, windowStart, windowEnd, 1));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DEVICE_EXISTS", "设备已登记: " + request.deviceId());
            }
            return DeviceView.of(new Device(request.deviceId(), request.model(),
                    request.currentVersion(), request.bucketNo(), offset, windowStart, windowEnd, 1));
        }, DeviceView.class);
    }

    /**
     * 修订偏移与窗口：expectedVersion 与设备当前 deviceVersion 不一致返回 409；成功后版本加一。
     * 仅改设备表，不触碰任务、回执与顺延记录。
     */
    public DeviceView updateWindow(String deviceId, UpdateDeviceWindowRequest request) {
        if (request.windowStartMinute() == request.windowEndMinute()) {
            throw ApiException.badRequest("WINDOW_START_END_SAME",
                    "维护窗口开始分钟与结束分钟不能相同（起大于止表示跨零点）");
        }
        String fingerprint = String.join("|", "device.updateWindow", deviceId,
                String.valueOf(request.expectedVersion()), String.valueOf(request.utcOffsetMinutes()),
                String.valueOf(request.windowStartMinute()), String.valueOf(request.windowEndMinute()));
        return idempotency.execute(request.requestId(), "device.updateWindow", fingerprint, () -> {
            Device device = deviceRepository.findById(deviceId)
                    .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
            int updated = deviceRepository.updateWindow(deviceId, request.expectedVersion(),
                    request.utcOffsetMinutes(), request.windowStartMinute(), request.windowEndMinute());
            if (updated == 0) {
                throw ApiException.conflict("DEVICE_VERSION_CONFLICT",
                        "expectedVersion 与设备当前版本不一致: " + device.deviceVersion());
            }
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
