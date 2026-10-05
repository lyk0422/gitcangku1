package com.example.starter.maintenance.service;

import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.ComponentResponse;
import com.example.starter.maintenance.api.dto.ComponentStatusResponse;
import com.example.starter.maintenance.api.dto.InstallComponentRequest;
import com.example.starter.maintenance.api.dto.InstallResponse;
import com.example.starter.maintenance.api.dto.RegisterComponentRequest;
import com.example.starter.maintenance.api.dto.UninstallComponentRequest;
import com.example.starter.maintenance.api.dto.UninstallResponse;

/**
 * 部件装拆账外观服务：委托事务服务执行；并发下唯一键冲突（事务已回滚）时，
 * 优先按 requestId 重放已提交的成功结果，否则转换为 409 业务冲突，不向调用方泄露唯一键异常。
 */
@Service
public class ComponentService {

    private final ComponentTxService txService;
    private final IdempotencyService idempotency;

    public ComponentService(ComponentTxService txService, IdempotencyService idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    public ComponentResponse register(RegisterComponentRequest req) {
        String fingerprint = Fingerprints.of("REGISTER_COMPONENT", req.operator(), req.componentId(),
                Map.of("lifetimeMinutes", req.lifetimeMinutes()));
        return recoverDuplicateKey(req.requestId(), "REGISTER_COMPONENT", fingerprint,
                ComponentResponse.class, () -> txService.register(req));
    }

    public InstallResponse install(String componentId, InstallComponentRequest req) {
        String fingerprint = Fingerprints.of("INSTALL_COMPONENT", req.operator(), componentId,
                Map.of("expectedVersion", req.expectedVersion(),
                        "equipmentId", req.equipmentId(),
                        "readingId", req.readingId()));
        return recoverDuplicateKey(req.requestId(), "INSTALL_COMPONENT", fingerprint,
                InstallResponse.class, () -> txService.install(componentId, req));
    }

    public UninstallResponse uninstall(String componentId, UninstallComponentRequest req) {
        String fingerprint = Fingerprints.of("UNINSTALL_COMPONENT", req.operator(), componentId,
                Map.of("expectedVersion", req.expectedVersion(), "readingId", req.readingId()));
        return recoverDuplicateKey(req.requestId(), "UNINSTALL_COMPONENT", fingerprint,
                UninstallResponse.class, () -> txService.uninstall(componentId, req));
    }

    public ComponentStatusResponse getStatus(String componentId) {
        return txService.getStatus(componentId);
    }

    /**
     * 并发唯一键冲突补偿：冲突事务已回滚，若同 requestId 的成功记录已提交则重放（异参抛 409），
     * 否则说明是业务唯一键冲突（如部件编号或在装归属被占用），返回 409。
     */
    private <T> T recoverDuplicateKey(String requestId, String operation, String fingerprint,
                                      Class<T> type, Supplier<T> action) {
        try {
            return action.get();
        } catch (DuplicateKeyException e) {
            T replayed = idempotency.replayExisting(requestId, operation, fingerprint, type);
            if (replayed != null) {
                return replayed;
            }
            throw ApiException.conflict("DUPLICATE_KEY", "唯一约束冲突，请核对业务标识是否已被占用");
        }
    }
}
