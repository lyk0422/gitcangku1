package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateFirmwareVersionRequest;
import com.example.starter.firmware.api.FirmwareVersionView;
import com.example.starter.firmware.domain.FirmwareVersion;
import com.example.starter.firmware.domain.ProfileCompatibility;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.FirmwareVersionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 固件版本兼容声明：型号集合、最低内存与必需能力标签。创建后不可修改。
 */
@Service
public class FirmwareVersionService {

    private final FirmwareVersionRepository versionRepository;
    private final IdempotencyService idempotency;

    public FirmwareVersionService(FirmwareVersionRepository versionRepository,
                                  IdempotencyService idempotency) {
        this.versionRepository = versionRepository;
        this.idempotency = idempotency;
    }

    public FirmwareVersionView create(CreateFirmwareVersionRequest request) {
        Set<String> models = ProfileCompatibility.normalizeSet(request.models());
        if (models.isEmpty()) {
            throw ApiException.badRequest("EMPTY_MODELS", "型号集合至少包含一个型号");
        }
        Set<String> requiredTags = ProfileCompatibility.normalizeSet(request.requiredTags());
        String fingerprint = String.join("|", "fw.version.create", request.name(),
                ProfileCompatibility.join(models), String.valueOf(request.minMemoryMb()),
                ProfileCompatibility.join(requiredTags));
        return idempotency.execute(request.requestId(), "fw.version.create", fingerprint, () -> {
            long id;
            try {
                id = versionRepository.insert(request.name(), models, request.minMemoryMb(),
                        requiredTags);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("VERSION_NAME_EXISTS", "版本名已存在: " + request.name());
            }
            return FirmwareVersionView.of(findVersion(id));
        }, FirmwareVersionView.class);
    }

    public FirmwareVersion findVersion(long versionId) {
        return versionRepository.findById(versionId)
                .orElseThrow(() -> ApiException.notFound("VERSION_NOT_FOUND", "固件版本不存在: " + versionId));
    }
}
