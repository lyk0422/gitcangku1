package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CompatibilityRequest;
import com.example.starter.firmware.api.FirmwareVersionView;
import com.example.starter.firmware.api.RegisterFirmwareRequest;
import com.example.starter.firmware.domain.FirmwareVersion;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CompatibilityRepository;
import com.example.starter.firmware.repo.FirmwareVersionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 固件版本登记与兼容矩阵维护。兼容矩阵只登记事实，不影响发布单生命周期；
 * 启动发布与设备拉取时由校验逻辑读取。未配置的升级路径沿用历史放行，存在显式 allowed=0 记录则拒绝。
 */
@Service
public class FirmwareService {

    private final FirmwareVersionRepository firmwareRepository;
    private final CompatibilityRepository compatibilityRepository;
    private final IdempotencyService idempotency;

    public FirmwareService(FirmwareVersionRepository firmwareRepository,
                           CompatibilityRepository compatibilityRepository,
                           IdempotencyService idempotency) {
        this.firmwareRepository = firmwareRepository;
        this.compatibilityRepository = compatibilityRepository;
        this.idempotency = idempotency;
    }

    public FirmwareVersionView register(RegisterFirmwareRequest request) {
        String fingerprint = String.join("|", "firmware.register", request.model(),
                request.version(), request.packageUri());
        return idempotency.execute(request.requestId(), "firmware.register", fingerprint, () -> {
            long id;
            try {
                id = firmwareRepository.insert(request.model(), request.version(), request.packageUri());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("FIRMWARE_EXISTS",
                        "固件版本已登记: " + request.model() + "/" + request.version());
            }
            return FirmwareVersionView.of(firmwareRepository.findById(id).orElseThrow());
        }, FirmwareVersionView.class);
    }

    public FirmwareVersionView putCompatibility(CompatibilityRequest request) {
        String fingerprint = String.join("|", "firmware.compatibility", request.model(),
                request.fromVersion(), request.toVersion(), String.valueOf(request.allowed()));
        return idempotency.execute(request.requestId(), "firmware.compatibility", fingerprint, () -> {
            if (request.fromVersion().equals(request.toVersion())) {
                throw ApiException.badRequest("SAME_VERSION", "来源版本与目标版本必须不同");
            }
            FirmwareVersion firmware = firmwareRepository
                    .findByModelVersion(request.model(), request.toVersion())
                    .orElseThrow(() -> ApiException.notFound("FIRMWARE_NOT_FOUND",
                            "目标固件版本未登记: " + request.model() + "/" + request.toVersion()));
            compatibilityRepository.upsert(request.model(), request.fromVersion(),
                    request.toVersion(), request.allowed());
            return FirmwareVersionView.of(firmware);
        }, FirmwareVersionView.class);
    }

    /**
     * 兼容矩阵校验：存在显式不兼容记录则拒绝；未配置或显式允许均放行（沿用历史默认行为）。
     */
    public void assertCompatible(String model, String fromVersion, String toVersion) {
        compatibilityRepository.find(model, fromVersion, toVersion).ifPresent(path -> {
            if (!path.allowed()) {
                throw ApiException.conflict("FIRMWARE_INCOMPATIBLE",
                        "固件升级路径不兼容: " + model + " " + fromVersion + " -> " + toVersion);
            }
        });
    }

    public FirmwareVersion requireFirmware(String model, String version) {
        return firmwareRepository.findByModelVersion(model, version)
                .orElseThrow(() -> ApiException.notFound("FIRMWARE_NOT_FOUND",
                        "固件版本未登记: " + model + "/" + version));
    }

    public FirmwareVersion findFirmware(String model, String version) {
        return firmwareRepository.findByModelVersion(model, version).orElse(null);
    }
}
