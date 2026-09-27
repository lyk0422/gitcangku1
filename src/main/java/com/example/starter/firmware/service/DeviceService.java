package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceView;
import com.example.starter.firmware.api.RegisterDeviceRequest;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.TreeMap;

/**
 * 设备登记与查询。型号与分桶号登记后不可修改；设备标签通过批量标签接口维护。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotency;
    private final Fingerprints fingerprints;

    public DeviceService(DeviceRepository deviceRepository, IdempotencyService idempotency,
                         Fingerprints fingerprints) {
        this.deviceRepository = deviceRepository;
        this.idempotency = idempotency;
        this.fingerprints = fingerprints;
    }

    public DeviceView register(RegisterDeviceRequest request) {
        TreeMap<String, Object> fp = fingerprints.builder("device.register");
        fp.put("deviceId", request.deviceId());
        fp.put("model", request.model());
        fp.put("currentVersion", request.currentVersion());
        fp.put("bucketNo", request.bucketNo());
        String fingerprint = fingerprints.canonical(fp);
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

    public Device findDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "设备不存在: " + deviceId));
    }
}
