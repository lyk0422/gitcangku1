package com.example.starter.maintenance.service;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CancelDowntimeRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.DowntimeResponse;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterDowntimeRequest;
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
    private final IdempotencyService idempotency;

    public EquipmentService(EquipmentTxService txService, IdempotencyService idempotency) {
        this.txService = txService;
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

    /** downtimeKey 全局唯一：并发跨设备撞键时优先重放同 requestId 的成功结果，否则转 409。 */
    public DowntimeResponse registerDowntime(String equipmentId, RegisterDowntimeRequest req) {
        String fingerprint = equipmentId + "|" + req.downtimeKey() + "|" + req.startAt() + "|"
                + req.endAt() + "|" + req.reason() + "|" + req.expectedVersion();
        return recoverDuplicateKey(req.requestId(), "REGISTER_DOWNTIME", fingerprint,
                DowntimeResponse.class, () -> txService.registerDowntime(equipmentId, req));
    }

    public DowntimeResponse cancelDowntime(String equipmentId, String downtimeKey, CancelDowntimeRequest req) {
        return txService.cancelDowntime(equipmentId, downtimeKey, req);
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

    public List<DowntimeResponse> listDowntimes(String equipmentId) {
        return txService.listDowntimes(equipmentId);
    }

    public DowntimeResponse getDowntime(String equipmentId, String downtimeKey) {
        return txService.getDowntime(equipmentId, downtimeKey);
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
