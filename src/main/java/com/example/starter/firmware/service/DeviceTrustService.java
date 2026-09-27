package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeviceTrustRequest;
import com.example.starter.firmware.api.DeviceTrustView;
import com.example.starter.firmware.domain.DeviceTrust;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceTrustRepository;
import org.springframework.stereotype.Service;

/**
 * 设备信任集合维护与校验。信任状态以最新登记为准（可在信任/不信任间切换）；
 * 未登记信任记录的设备视为历史设备，不阻断未启用证书固件的既有流程。
 */
@Service
public class DeviceTrustService {

    private final DeviceTrustRepository trustRepository;
    private final IdempotencyService idempotency;

    public DeviceTrustService(DeviceTrustRepository trustRepository, IdempotencyService idempotency) {
        this.trustRepository = trustRepository;
        this.idempotency = idempotency;
    }

    public DeviceTrustView put(String deviceId, DeviceTrustRequest request) {
        String fingerprint = String.join("|", "device.trust", deviceId, request.anchorFingerprint(),
                String.valueOf(request.trusted()), request.operator());
        return idempotency.execute(request.requestId(), "device.trust", fingerprint, () -> {
            trustRepository.upsert(deviceId, request.anchorFingerprint(), request.trusted());
            return DeviceTrustView.of(trustRepository.findByDevice(deviceId).orElseThrow());
        }, DeviceTrustView.class);
    }

    public DeviceTrustView get(String deviceId) {
        return DeviceTrustView.of(trustRepository.findByDevice(deviceId)
                .orElseThrow(() -> ApiException.notFound("TRUST_NOT_FOUND",
                        "设备信任记录不存在: " + deviceId)));
    }

    /**
     * 设备最终信任状态；未登记记录返回 null（由调用方按历史设备处理）。
     */
    public DeviceTrust find(String deviceId) {
        return trustRepository.findByDevice(deviceId).orElse(null);
    }
}
