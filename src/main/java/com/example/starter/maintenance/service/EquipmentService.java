package com.example.starter.maintenance.service;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.CorrectionCreateRequest;
import com.example.starter.maintenance.api.dto.CorrectionResponse;
import com.example.starter.maintenance.api.dto.CycleResponse;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.EvidencePackageResponse;
import com.example.starter.maintenance.api.dto.EvidenceSubmitRequest;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.MaterialRegisterRequest;
import com.example.starter.maintenance.api.dto.MaterialResponse;
import com.example.starter.maintenance.api.dto.MaterialRevokeRequest;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.api.dto.TemplateResponse;
import com.example.starter.maintenance.api.dto.TemplateRevisionRequest;

/**
 * 设备工时保养外观服务：委托事务服务执行；并发下唯一键冲突（事务已回滚）时，
 * 优先按 requestId 重放已提交的成功结果，否则转换为 409 业务冲突。
 */
@Service
public class EquipmentService {

    private final EquipmentTxService txService;
    private final IdempotencyService idempotency;

    public EquipmentService(EquipmentTxService txService, IdempotencyService idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    public EquipmentResponse register(RegisterEquipmentRequest req) {
        String fingerprint = req.equipmentId() + "|" + req.maintenancePeriodMinutes() + "|"
                + req.overdueThresholdMinutes();
        return recoverDuplicateKey(req.requestId(), "REGISTER_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> txService.register(req));
    }

    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        return txService.addReading(equipmentId, req);
    }

    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        return txService.reviseReading(equipmentId, readingId, req);
    }

    public TemplateResponse reviseTemplate(String equipmentId, TemplateRevisionRequest req) {
        return txService.reviseTemplate(equipmentId, req);
    }

    public EvidencePackageResponse submitEvidence(String equipmentId, EvidenceSubmitRequest req) {
        return txService.submitEvidence(equipmentId, req);
    }

    public MaterialResponse registerMaterial(MaterialRegisterRequest req) {
        String fingerprint = req.materialId() + "|" + req.materialName() + "|" + req.unit();
        return recoverDuplicateKey(req.requestId(), "REGISTER_MATERIAL", fingerprint,
                MaterialResponse.class, () -> txService.registerMaterial(req));
    }

    public MaterialResponse revokeMaterial(String materialId, MaterialRevokeRequest req) {
        return txService.revokeMaterial(materialId, req);
    }

    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        return txService.completeMaintenance(equipmentId, req);
    }

    public CorrectionResponse addCorrection(long maintenanceId, CorrectionCreateRequest req) {
        return txService.addCorrection(maintenanceId, req);
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

    public TemplateResponse getTemplate(String equipmentId) {
        return txService.getTemplate(equipmentId);
    }

    public List<EvidencePackageResponse> listEvidence(String equipmentId, int cycleNo) {
        return txService.listEvidence(equipmentId, cycleNo);
    }

    public EvidencePackageResponse getEvidence(String equipmentId, int cycleNo, int packageVersion) {
        return txService.getEvidence(equipmentId, cycleNo, packageVersion);
    }

    public CycleResponse getCurrentCycle(String equipmentId) {
        return txService.getCurrentCycle(equipmentId);
    }

    public List<CycleResponse> listCycles(String equipmentId) {
        return txService.listCycles(equipmentId);
    }

    public List<MaterialResponse> listMaterials() {
        return txService.listMaterials();
    }

    public List<CorrectionResponse> listCorrections(String equipmentId, Long maintenanceId) {
        return txService.listCorrections(equipmentId, maintenanceId);
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
