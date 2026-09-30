package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.FailureRoundView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceFailureRoundRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备登记与查询。型号与分桶号登记后不可修改。
 * 查询返回各目标版本的连续失败轮次：本轮连续失败次数、隔离状态、隔离依据与解除记录。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final DeviceFailureRoundRepository roundRepository;
    private final IdempotencyService idempotency;

    public DeviceService(DeviceRepository deviceRepository, DeviceFailureRoundRepository roundRepository,
                         IdempotencyService idempotency) {
        this.deviceRepository = deviceRepository;
        this.roundRepository = roundRepository;
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
                    request.bucketNo(), List.of());
        }, DeviceView.class);
    }

    public DeviceView get(String deviceId) {
        Device device = findDevice(deviceId);
        List<FailureRoundView> rounds = roundRepository.findByDevice(deviceId).stream()
                .map(FailureRoundView::of).toList();
        return DeviceView.of(device, rounds);
    }

    public Device findDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }
}
