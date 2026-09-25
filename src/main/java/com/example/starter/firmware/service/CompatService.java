package com.example.starter.firmware.service;

import com.example.starter.firmware.api.HardwareModelView;
import com.example.starter.firmware.api.MatrixView;
import com.example.starter.firmware.api.RegisterHardwareModelRequest;
import com.example.starter.firmware.api.UpdateMatrixRequest;
import com.example.starter.firmware.domain.CompatMatrix;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CompatMatrixRepository;
import com.example.starter.firmware.repo.HardwareModelRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 固件硬件兼容矩阵：已知硬件型号目录、矩阵查询与配置修改。
 * 空型号集合表示兼容全部硬件型号；集合换序视为同参（幂等且不产生新版本）；
 * 型号重复或未知返回 422；配置修改携带固件 expectedVersion，版本不符返回 409。
 * 矩阵版本仅在允许型号集合内容真正变化时加一，缩窄只影响后续拉取与启动预检。
 */
@Service
public class CompatService {

    private final HardwareModelRepository hardwareModelRepository;
    private final CompatMatrixRepository matrixRepository;
    private final IdempotencyService idempotency;

    public CompatService(HardwareModelRepository hardwareModelRepository,
                         CompatMatrixRepository matrixRepository, IdempotencyService idempotency) {
        this.hardwareModelRepository = hardwareModelRepository;
        this.matrixRepository = matrixRepository;
        this.idempotency = idempotency;
    }

    public HardwareModelView registerHardwareModel(RegisterHardwareModelRequest request) {
        String fingerprint = String.join("|", "hardware.register", request.hardwareModel());
        return idempotency.execute(request.requestId(), "hardware.register", fingerprint, () -> {
            boolean registered = hardwareModelRepository.register(request.hardwareModel());
            return new HardwareModelView(request.hardwareModel(), registered);
        }, HardwareModelView.class);
    }

    public List<String> listHardwareModels() {
        return hardwareModelRepository.findAll();
    }

    /**
     * 查询矩阵：未配置返回版本 0、兼容全部型号。
     */
    public MatrixView getMatrix(String firmwareVersion) {
        return matrixRepository.findByFirmware(firmwareVersion)
                .map(MatrixView::of)
                .orElseGet(() -> MatrixView.unconfigured(firmwareVersion));
    }

    /**
     * 配置修改：校验在事务外先行（422 不占键），版本与写入在幂等事务内完成。
     */
    public MatrixView updateMatrix(UpdateMatrixRequest request) {
        List<String> requested = request.models() == null ? List.of() : request.models();
        Set<String> seen = new HashSet<>();
        for (String model : requested) {
            if (model == null || model.isBlank() || !seen.add(model)) {
                throw ApiException.unprocessable("MATRIX_DUPLICATE_MODEL",
                        "允许的硬件型号不可重复: " + model);
            }
        }
        for (String model : requested) {
            if (!hardwareModelRepository.exists(model)) {
                throw ApiException.unprocessable("MATRIX_UNKNOWN_MODEL", "未知硬件型号: " + model);
            }
        }
        List<String> sorted = requested.stream().sorted().toList();
        String modelsFingerprint = String.join(",", sorted);
        String fingerprint = String.join("|", "matrix.update", request.firmwareVersion(),
                String.valueOf(request.expectedVersion()), modelsFingerprint);
        return idempotency.execute(request.requestId(), "matrix.update", fingerprint, () -> {
            var existing = matrixRepository.findByFirmwareForUpdate(request.firmwareVersion());
            if (existing.isEmpty()) {
                if (request.expectedVersion() != 0) {
                    throw ApiException.conflict("MATRIX_VERSION_CONFLICT",
                            "矩阵尚未配置，expectedVersion 必须为 0");
                }
                try {
                    matrixRepository.insert(request.firmwareVersion(), sorted);
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("MATRIX_VERSION_CONFLICT", "矩阵已被并发创建，请重试");
                }
            } else {
                CompatMatrix current = existing.get();
                if (current.version() != request.expectedVersion()) {
                    throw ApiException.conflict("MATRIX_VERSION_CONFLICT",
                            "expectedVersion 与当前矩阵版本不一致: " + current.version());
                }
                // 集合内容相同（含换序）视为同参：不产生新版本
                if (!new TreeSet<>(current.models()).equals(new TreeSet<>(sorted))) {
                    if (matrixRepository.update(request.firmwareVersion(), request.expectedVersion(), sorted) != 1) {
                        throw ApiException.conflict("MATRIX_VERSION_CONFLICT", "矩阵已被并发修改，请重试");
                    }
                }
            }
            return MatrixView.of(matrixRepository.findByFirmware(request.firmwareVersion()).orElseThrow());
        }, MatrixView.class);
    }

    /**
     * 设备硬件型号是否被该固件矩阵兼容：未配置矩阵或空集合型号兼容全部。
     */
    public boolean isCompatible(CompatMatrix matrix, String hardwareModel) {
        return matrix.models().isEmpty() || matrix.models().contains(hardwareModel);
    }

    /**
     * 拉取时刻生效的兼容矩阵快照；未配置以版本 0 表示（兼容全部型号）。
     * 调用方持有发布单行锁；启动预检调用方持有矩阵行锁以与配置修改按提交顺序裁决。
     */
    public CompatMatrix effectiveMatrix(String firmwareVersion) {
        return matrixRepository.findByFirmware(firmwareVersion)
                .orElseGet(() -> new CompatMatrix(firmwareVersion, 0, List.of()));
    }

    /**
     * 启动预检路径：对目标固件矩阵加行锁，阻塞并发缩窄直至启动事务提交。
     */
    public CompatMatrix effectiveMatrixForUpdate(String firmwareVersion) {
        return matrixRepository.findByFirmwareForUpdate(firmwareVersion)
                .orElseGet(() -> new CompatMatrix(firmwareVersion, 0, List.of()));
    }
}
