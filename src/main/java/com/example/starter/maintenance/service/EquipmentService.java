package com.example.starter.maintenance.service;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.AppendixView;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.CreateAppendixRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.EvidenceResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.MaterialResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.RegisterMaterialRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.RevokeMaterialRequest;
import com.example.starter.maintenance.api.dto.SaveTemplateRequest;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.api.dto.SubmitEvidenceRequest;
import com.example.starter.maintenance.api.dto.TemplateResponse;

/**
 * 设备工时保养外观服务：委托事务服务执行；并发下唯一键冲突（事务已回滚）时，
 * 优先按 requestId 重放已提交的成功结果，否则转换为 409 业务冲突。
 */
@Service
public class EquipmentService {

    private final EquipmentTxService txService;
    private final EvidenceTxService evidenceTxService;
    private final IdempotencyService idempotency;

    public EquipmentService(EquipmentTxService txService,
                            EvidenceTxService evidenceTxService,
                            IdempotencyService idempotency) {
        this.txService = txService;
        this.evidenceTxService = evidenceTxService;
        this.idempotency = idempotency;
    }

    public EquipmentResponse register(RegisterEquipmentRequest req) {
        String fingerprint = req.equipmentId() + "|" + req.maintenancePeriodMinutes();
        return recoverDuplicateKey(req.requestId(), "REGISTER_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> txService.register(req));
    }

    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        return txService.addReading(equipmentId, req);
    }

    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        return txService.reviseReading(equipmentId, readingId, req);
    }

    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        return txService.completeMaintenance(equipmentId, req);
    }

    public StatusResponse getStatus(String equipmentId) {
        return txService.getStatus(equipmentId);
    }

    public List<ReadingResponse> listReadings(String equipmentId) {
        return txService.listReadings(equipmentId);
    }

    public List<RevisionView> listRevisions(String equipmentId, String readingId) {
        return txService.listRevisions(equipmentId, readingId);
    }

    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        return txService.listMaintenances(equipmentId);
    }

    // ---------- 模板 / 材料 / 证据包 / 附录 ----------

    public TemplateResponse saveTemplate(String equipmentId, SaveTemplateRequest req) {
        return evidenceTxService.saveTemplate(equipmentId, req);
    }

    public TemplateResponse getLatestTemplate(String equipmentId) {
        return evidenceTxService.getLatestTemplate(equipmentId);
    }

    public TemplateResponse getTemplate(String equipmentId, int templateVersion) {
        return evidenceTxService.getTemplate(equipmentId, templateVersion);
    }

    public MaterialResponse registerMaterial(RegisterMaterialRequest req) {
        String fingerprint = req.batchCode() + "|" + req.description();
        try {
            return evidenceTxService.registerMaterial(req);
        } catch (DuplicateKeyException e) {
            MaterialResponse replayed = idempotency.replayExisting(
                    req.requestId(), "REGISTER_MATERIAL", fingerprint, MaterialResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw ApiException.conflict("DUPLICATE_KEY", "唯一约束冲突，请核对业务标识是否已被占用");
        }
    }

    public MaterialResponse revokeMaterial(String batchCode, RevokeMaterialRequest req) {
        String fingerprint = batchCode + "|" + req.reason();
        try {
            return evidenceTxService.revokeMaterial(batchCode, req);
        } catch (ApiException e) {
            // 无设备行锁：并发同 requestId 撤销时败者事务会看到材料已撤销，优先重放首次成功结果
            if ("MATERIAL_ALREADY_REVOKED".equals(e.code())) {
                MaterialResponse replayed = idempotency.replayExisting(
                        req.requestId(), "REVOKE_MATERIAL", fingerprint, MaterialResponse.class);
                if (replayed != null) {
                    return replayed;
                }
            }
            throw e;
        }
    }

    public MaterialResponse getMaterial(String batchCode) {
        return evidenceTxService.getMaterial(batchCode);
    }

    public EvidenceResponse submitEvidence(String equipmentId, SubmitEvidenceRequest req) {
        return evidenceTxService.submitEvidence(equipmentId, req);
    }

    public EvidenceResponse getCurrentEvidence(String equipmentId) {
        return evidenceTxService.getCurrentEvidence(equipmentId);
    }

    public EvidenceResponse getCycleEvidence(String equipmentId, int cycleNo) {
        return evidenceTxService.getEvidence(equipmentId, cycleNo);
    }

    public EvidenceResponse getEvidenceVersion(String equipmentId, int cycleNo, int packageVersion) {
        return evidenceTxService.getEvidenceVersion(equipmentId, cycleNo, packageVersion);
    }

    public AppendixView createAppendix(String equipmentId, int cycleNo, CreateAppendixRequest req) {
        return evidenceTxService.createAppendix(equipmentId, cycleNo, req);
    }

    public List<AppendixView> listAppendices(String equipmentId, int cycleNo) {
        return evidenceTxService.listAppendices(equipmentId, cycleNo);
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
