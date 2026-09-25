package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ConfigureCompatRequest;
import com.example.starter.firmware.api.FirmwareCompatView;
import com.example.starter.firmware.domain.FirmwareCompat;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.FirmwareCompatRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 固件硬件兼容矩阵：按固件版本配置允许的硬件型号集合，空集合表示兼容全部型号。
 * 修改携带 expectedVersion 乐观校验，矩阵行锁串行化并发修改；型号重复或未知返回 422。
 */
@Service
public class FirmwareCompatService {

    private final FirmwareCompatRepository compatRepository;
    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotency;

    public FirmwareCompatService(FirmwareCompatRepository compatRepository,
                                 DeviceRepository deviceRepository, IdempotencyService idempotency) {
        this.compatRepository = compatRepository;
        this.deviceRepository = deviceRepository;
        this.idempotency = idempotency;
    }

    /**
     * 配置兼容矩阵。集合升序去重后存储，换序视为同参；首次配置要求 expectedVersion 为 0。
     */
    public FirmwareCompatView configure(String firmwareVersion, ConfigureCompatRequest request) {
        List<String> sorted = normalize(request.allowedModels());
        String fingerprint = String.join("|", "firmware.compat", firmwareVersion,
                String.valueOf(request.expectedVersion()), String.join(",", sorted));
        return idempotency.execute(request.requestId(), "firmware.compat", fingerprint, () -> {
            validateModels(request.allowedModels());
            FirmwareCompat current = compatRepository.findForUpdate(firmwareVersion)
                    .orElse(FirmwareCompat.unconfigured(firmwareVersion));
            if (current.matrixVersion() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前矩阵版本不一致: " + current.matrixVersion());
            }
            if (current.matrixVersion() == 0) {
                try {
                    compatRepository.insert(firmwareVersion, sorted);
                } catch (DuplicateKeyException e) {
                    // 并发首次配置：对方已提交版本 1，按提交顺序本请求视为版本冲突
                    throw ApiException.conflict("VERSION_CONFLICT",
                            "expectedVersion 与当前矩阵版本不一致: 1");
                }
            } else {
                compatRepository.update(firmwareVersion, request.expectedVersion(), sorted);
            }
            return FirmwareCompatView.of(compatRepository.find(firmwareVersion).orElseThrow());
        }, FirmwareCompatView.class);
    }

    /**
     * 查询矩阵版本与允许集合；未配置时返回版本 0 与空集合（兼容全部型号）。
     */
    public FirmwareCompatView get(String firmwareVersion) {
        return FirmwareCompatView.of(findCompat(firmwareVersion));
    }

    public FirmwareCompat findCompat(String firmwareVersion) {
        return compatRepository.find(firmwareVersion)
                .orElse(FirmwareCompat.unconfigured(firmwareVersion));
    }

    /**
     * 升序去重；换序与重复不影响指纹与存储内容。
     */
    private List<String> normalize(List<String> allowedModels) {
        return List.copyOf(new TreeSet<>(allowedModels));
    }

    /**
     * 型号重复或未知（无任何已登记设备使用该硬件型号）返回 422。
     */
    private void validateModels(List<String> allowedModels) {
        Set<String> seen = new HashSet<>();
        for (String model : allowedModels) {
            if (!seen.add(model)) {
                throw ApiException.unprocessable("DUPLICATE_MODEL", "硬件型号重复: " + model);
            }
        }
        if (seen.isEmpty()) {
            return;
        }
        Set<String> known = new HashSet<>(deviceRepository.findDistinctHardwareModels());
        for (String model : seen) {
            if (!known.contains(model)) {
                throw ApiException.unprocessable("UNKNOWN_MODEL", "未知硬件型号: " + model);
            }
        }
    }
}
