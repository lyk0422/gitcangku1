package com.example.starter.firmware.service;

import com.example.starter.firmware.api.DeclareHardwareRequest;
import com.example.starter.firmware.api.HardwareRequirementView;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.HardwareRequirementRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 固件版本硬件兼容要求声明：型号集合、最低内存、必需能力标签。要求只增不改，重复声明返回 409。
 */
@Service
public class HardwareRequirementService {

    private final HardwareRequirementRepository repository;
    private final IdempotencyService idempotency;

    public HardwareRequirementService(HardwareRequirementRepository repository, IdempotencyService idempotency) {
        this.repository = repository;
        this.idempotency = idempotency;
    }

    public HardwareRequirementView declare(DeclareHardwareRequest request) {
        List<String> models = request.models().stream().distinct().sorted().toList();
        List<String> tags = request.effectiveTags().stream().distinct().sorted().toList();
        String fingerprint = String.join("|", "hardware.declare", request.firmwareVersion(),
                String.valueOf(request.minMemoryMb()), String.join(",", models), String.join(",", tags));
        return idempotency.execute(request.requestId(), "hardware.declare", fingerprint, () -> {
            try {
                repository.insert(request.firmwareVersion(), request.minMemoryMb(), models, tags);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("HARDWARE_REQUIREMENT_EXISTS",
                        "固件版本已声明硬件要求，不能修改: " + request.firmwareVersion());
            }
            return HardwareRequirementView.of(
                    repository.findByVersion(request.firmwareVersion())
                            .orElseThrow(() -> new IllegalStateException("硬件要求声明后读取失败")));
        }, HardwareRequirementView.class);
    }

    public HardwareRequirementView get(String firmwareVersion) {
        return repository.findByVersion(firmwareVersion)
                .map(HardwareRequirementView::of)
                .orElseThrow(() -> ApiException.notFound("HARDWARE_REQUIREMENT_NOT_FOUND",
                        "固件版本未声明硬件要求: " + firmwareVersion));
    }
}
