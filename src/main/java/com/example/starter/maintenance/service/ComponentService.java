package com.example.starter.maintenance.service;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.ComponentInstallView;
import com.example.starter.maintenance.api.dto.ComponentResponse;
import com.example.starter.maintenance.api.dto.InstallComponentRequest;
import com.example.starter.maintenance.api.dto.RegisterComponentRequest;
import com.example.starter.maintenance.api.dto.RemoveComponentRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 部件装拆账外观服务：委托事务服务执行；并发下唯一键冲突（事务已回滚）时，
 * 优先按 requestId 重放已提交的成功结果，否则转换为 409 业务冲突，不向调用方泄露唯一键异常。
 */
@Service
public class ComponentService {

    private final ComponentTxService txService;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public ComponentService(ComponentTxService txService, IdempotencyService idempotency,
                            ObjectMapper objectMapper) {
        this.txService = txService;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    public ComponentResponse register(RegisterComponentRequest req) {
        String fingerprint = Fingerprints.of(objectMapper, "REGISTER_COMPONENT", req.operator(),
                req.componentId(), Fingerprints.params("lifespanMinutes", req.lifespanMinutes()));
        return recoverDuplicateKey(req.requestId(), "REGISTER_COMPONENT", fingerprint,
                ComponentResponse.class, () -> txService.register(req));
    }

    public ComponentInstallView install(String componentId, InstallComponentRequest req) {
        String fingerprint = Fingerprints.of(objectMapper, "INSTALL_COMPONENT", req.operator(),
                componentId, Fingerprints.params("expectedVersion", req.expectedVersion(),
                        "equipmentId", req.equipmentId(), "readingId", req.readingId()));
        return recoverDuplicateKey(req.requestId(), "INSTALL_COMPONENT", fingerprint,
                ComponentInstallView.class, () -> txService.install(componentId, req));
    }

    public ComponentInstallView remove(String componentId, RemoveComponentRequest req) {
        String fingerprint = Fingerprints.of(objectMapper, "REMOVE_COMPONENT", req.operator(),
                componentId, Fingerprints.params("expectedVersion", req.expectedVersion(),
                        "equipmentId", req.equipmentId(), "readingId", req.readingId()));
        return recoverDuplicateKey(req.requestId(), "REMOVE_COMPONENT", fingerprint,
                ComponentInstallView.class, () -> txService.remove(componentId, req));
    }

    public ComponentResponse getComponent(String componentId) {
        return txService.getComponent(componentId);
    }

    public List<ComponentInstallView> listInstalls(String componentId) {
        return txService.listInstalls(componentId);
    }

    /**
     * 并发唯一键冲突补偿：冲突事务已回滚，若同 requestId 的成功记录已提交则重放（异参抛 409），
     * 否则说明是业务唯一键冲突（如部件号被占、同部件并发安装），返回 409。
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