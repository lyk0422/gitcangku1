package com.example.starter.maintenance.service;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AppendixResponse;
import com.example.starter.maintenance.api.dto.ConfirmCompletionRequest;
import com.example.starter.maintenance.api.dto.ConfirmationResponse;
import com.example.starter.maintenance.api.dto.CreateAppendixRequest;
import com.example.starter.maintenance.api.dto.CreateTemplateRequest;
import com.example.starter.maintenance.api.dto.DiagnosticsResponse;
import com.example.starter.maintenance.api.dto.EvidencePackageResponse;
import com.example.starter.maintenance.api.dto.EvidencePackageSummary;
import com.example.starter.maintenance.api.dto.MaterialBatchResponse;
import com.example.starter.maintenance.api.dto.RegisterMaterialRequest;
import com.example.starter.maintenance.api.dto.ReviseTemplateRequest;
import com.example.starter.maintenance.api.dto.RevokeMaterialRequest;
import com.example.starter.maintenance.api.dto.SubmitEvidenceRequest;
import com.example.starter.maintenance.api.dto.TemplateResponse;

/**
 * 保养证据包外观服务：委托事务服务执行；并发下唯一键冲突（事务已回滚）时，
 * 优先按 requestId 重放已提交的成功结果，否则转换为 409 业务冲突。
 */
@Service
public class EvidenceService {

    private final EvidenceTxService txService;
    private final IdempotencyService idempotency;

    public EvidenceService(EvidenceTxService txService, IdempotencyService idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    // ---------- 保养模板 ----------

    public TemplateResponse createTemplate(CreateTemplateRequest req) {
        String fingerprint = req.templateId() + "|" + EvidenceTxService.canonicalItems(req.items());
        return recoverDuplicateKey(req.requestId(), "CREATE_TEMPLATE", fingerprint,
                TemplateResponse.class, () -> txService.createTemplate(req));
    }

    public TemplateResponse reviseTemplate(String templateId, ReviseTemplateRequest req) {
        return txService.reviseTemplate(templateId, req);
    }

    public TemplateResponse getTemplate(String templateId) {
        return txService.getTemplate(templateId);
    }

    // ---------- 材料批次 ----------

    public MaterialBatchResponse registerMaterial(RegisterMaterialRequest req) {
        String fingerprint = req.batchCode() + "|" + req.materialName();
        return recoverDuplicateKey(req.requestId(), "REGISTER_MATERIAL", fingerprint,
                MaterialBatchResponse.class, () -> txService.registerMaterial(req));
    }

    public MaterialBatchResponse revokeMaterial(String batchCode, RevokeMaterialRequest req) {
        return txService.revokeMaterial(batchCode, req);
    }

    public MaterialBatchResponse getMaterial(String batchCode) {
        return txService.getMaterial(batchCode);
    }

    // ---------- 证据包与完工确认 ----------

    public EvidencePackageResponse submitEvidence(String equipmentId, SubmitEvidenceRequest req) {
        return txService.submitEvidence(equipmentId, req);
    }

    public ConfirmationResponse confirmCompletion(String equipmentId, ConfirmCompletionRequest req) {
        return txService.confirmCompletion(equipmentId, req);
    }

    public AppendixResponse createAppendix(String equipmentId, long maintenanceId,
                                           CreateAppendixRequest req) {
        return txService.createAppendix(equipmentId, maintenanceId, req);
    }

    public List<AppendixResponse> listAppendices(String equipmentId, long maintenanceId) {
        return txService.listAppendices(equipmentId, maintenanceId);
    }

    public EvidencePackageResponse getEvidencePackage(String equipmentId, Long cycleNo,
                                                      Integer packageVersion) {
        return txService.getEvidencePackage(equipmentId, cycleNo, packageVersion);
    }

    public List<EvidencePackageSummary> listEvidenceHistory(String equipmentId) {
        return txService.listEvidenceHistory(equipmentId);
    }

    public DiagnosticsResponse getDiagnostics(String equipmentId) {
        return txService.getDiagnostics(equipmentId);
    }

    /**
     * 并发唯一键冲突补偿：冲突事务已回滚，若同 requestId 的成功记录已提交则重放（异参抛 409），
     * 否则说明是业务唯一键冲突，返回 409。
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
