package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.QuarantineRoundView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.QuarantineRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 设备登记与查询。型号与分桶号登记后不可修改。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final QuarantineRepository quarantineRepository;
    private final IdempotencyService idempotency;

    public DeviceService(DeviceRepository deviceRepository, QuarantineRepository quarantineRepository,
                         IdempotencyService idempotency) {
        this.deviceRepository = deviceRepository;
        this.quarantineRepository = quarantineRepository;
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
            return DeviceView.of(new Device(request.deviceId(), request.model(),
                    request.currentVersion(), request.bucketNo()));
        }, DeviceView.class);
    }

    public DeviceView get(String deviceId) {
        Device device = findDevice(deviceId);
        List<QuarantineRoundView> rounds = quarantineRepository.findByDevice(deviceId).stream()
                .map(QuarantineRoundView::of).toList();
        return DeviceView.of(device, rounds);
    }

    public Device findDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }
}
