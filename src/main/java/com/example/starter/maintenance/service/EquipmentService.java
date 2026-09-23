package com.example.starter.maintenance.service;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.DriftCorrectionActivateRequest;
import com.example.starter.maintenance.api.dto.DriftCorrectionDetailView;
import com.example.starter.maintenance.api.dto.DriftCorrectionPreviewRequest;
import com.example.starter.maintenance.api.dto.DriftCorrectionPreviewResponse;
import com.example.starter.maintenance.api.dto.DriftCorrectionResponse;
import com.example.starter.maintenance.api.dto.DriftCorrectionSummaryView;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.MaintenanceSnapshotView;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;

/**
 * 设备工时保养外观服务：委托事务服务执行；并发下唯一键冲突（事务已回滚）时，
 * 优先按 requestId 重放已提交的成功结果，否则转换为 409 业务冲突。
 */
@Service
public class EquipmentService {

    private final EquipmentTxService txService;
    private final DriftCorrectionService driftCorrectionService;
    private final IdempotencyService idempotency;

    public EquipmentService(EquipmentTxService txService, DriftCorrectionService driftCorrectionService,
                            IdempotencyService idempotency) {
        this.txService = txService;
        this.driftCorrectionService = driftCorrectionService;
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

    // ---------- 漂移修正 ----------

    public DriftCorrectionPreviewResponse previewDriftCorrection(String equipmentId,
                                                                 DriftCorrectionPreviewRequest req) {
        return driftCorrectionService.preview(equipmentId, req);
    }

    /**
     * 激活漂移修正。correctionKey 全局唯一：并发唯一键冲突（事务已回滚）时，
     * 优先按 requestId 重放已提交的成功快照，否则说明 correctionKey 被其他请求占用，返回 409。
     */
    public DriftCorrectionResponse activateDriftCorrection(String equipmentId,
                                                           DriftCorrectionActivateRequest req) {
        String fingerprint = DriftCorrectionService.fingerprint(equipmentId, req);
        try {
            return driftCorrectionService.activate(equipmentId, req);
        } catch (DuplicateKeyException e) {
            DriftCorrectionResponse replayed = idempotency.replayExisting(req.requestId(),
                    DriftCorrectionService.OPERATION, fingerprint, DriftCorrectionResponse.class);
            if (replayed != null) {
                return replayed;
            }
            throw ApiException.conflict("CORRECTION_KEY_EXISTS",
                    "correctionKey 已被占用：" + req.correctionKey());
        }
    }

    public List<DriftCorrectionSummaryView> listDriftCorrections(String equipmentId) {
        return driftCorrectionService.listCorrections(equipmentId);
    }

    public DriftCorrectionDetailView getDriftCorrection(String equipmentId, String correctionKey) {
        return driftCorrectionService.getCorrection(equipmentId, correctionKey);
    }

    public List<MaintenanceSnapshotView> listMaintenanceSnapshots(String equipmentId) {
        return driftCorrectionService.listSnapshots(equipmentId);
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
