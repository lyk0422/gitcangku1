package com.example.starter.firmware.service;

import com.example.starter.firmware.dto.DeviceResponse;
import com.example.starter.firmware.dto.RegisterDeviceRequest;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repository.DeviceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 设备登记：deviceId 唯一，型号与分桶号登记后不可变。
 */
@Service
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotencyService;

    public DeviceService(DeviceRepository deviceRepository, IdempotencyService idempotencyService) {
        this.deviceRepository = deviceRepository;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 登记设备；同 requestId 同参数重放首次结果，deviceId 已存在返回 409。
     */
    public ApiResult register(RegisterDeviceRequest request) {
        String fingerprint = String.join("|", request.deviceId(), request.model(),
                request.firmwareVersion(), String.valueOf(request.bucketNo()));
        return idempotencyService.execute(request.requestId(), "DEVICE_REGISTER", fingerprint,
                () -> doRegister(request));
    }

    private ApiResult doRegister(RegisterDeviceRequest request) {
        try {
            deviceRepository.insert(request.deviceId(), request.model(),
                    request.firmwareVersion(), request.bucketNo());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DEVICE_ALREADY_EXISTS", "设备已登记: " + request.deviceId());
        }
        return ApiResult.created(new DeviceResponse(request.deviceId(), request.model(),
                request.firmwareVersion(), request.bucketNo()));
    }
}
