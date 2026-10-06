package com.example.starter.maintenance.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddRawReadingRequest;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.RegisterRangeRequest;
import com.example.starter.maintenance.api.dto.ReviseRawReadingRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.service.IdempotencyService.WriteIdentity;

/**
 * 设备工时保养外观服务：构造结构化幂等身份（操作、操作者、资源、全部参数与期望版本），
 * 委托事务服务执行；并发下唯一键冲突（事务已回滚）时，优先按 requestId 重放已提交的成功结果，
 * 否则转换为 409 业务冲突，不向调用方泄露唯一键异常。
 */
@Service
public class EquipmentService {

    private static final String OP_REGISTER = "REGISTER_EQUIPMENT";
    private static final String OP_REGISTER_RANGE = "REGISTER_RANGE";
    private static final String OP_ADD_READING = "ADD_READING";
    private static final String OP_ADD_RAW_READING = "ADD_RAW_READING";
    private static final String OP_REVISE_READING = "REVISE_READING";
    private static final String OP_REVISE_RAW_READING = "REVISE_RAW_READING";
    private static final String OP_COMPLETE_MAINTENANCE = "COMPLETE_MAINTENANCE";

    private final EquipmentTxService txService;
    private final IdempotencyService idempotency;

    public EquipmentService(EquipmentTxService txService, IdempotencyService idempotency) {
        this.txService = txService;
        this.idempotency = idempotency;
    }

    public EquipmentResponse register(RegisterEquipmentRequest req) {
        WriteIdentity identity = idempotency.identity(OP_REGISTER,
                "equipment/" + req.equipmentId(), req.operator(),
                orderedMap("equipmentId", req.equipmentId(),
                        "maintenancePeriodMinutes", req.maintenancePeriodMinutes()));
        return recoverDuplicateKey(req.requestId(), identity, EquipmentResponse.class,
                () -> txService.register(req, identity));
    }

    public EquipmentResponse registerRange(String equipmentId, RegisterRangeRequest req) {
        WriteIdentity identity = idempotency.identity(OP_REGISTER_RANGE,
                "equipment/" + equipmentId + "/range", req.operator(),
                orderedMap("equipmentId", equipmentId,
                        "expectedVersion", req.expectedVersion(),
                        "rangeM", req.rangeM()));
        return recoverDuplicateKey(req.requestId(), identity, EquipmentResponse.class,
                () -> txService.registerRange(equipmentId, req, identity));
    }

    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        WriteIdentity identity = idempotency.identity(OP_ADD_READING,
                "equipment/" + equipmentId + "/readings/" + req.readingId(), req.operator(),
                orderedMap("equipmentId", equipmentId,
                        "readingId", req.readingId(),
                        "sampledAt", req.sampledAt(),
                        "cumulativeMinutes", req.cumulativeMinutes(),
                        "expectedVersion", req.expectedVersion()));
        return recoverDuplicateKey(req.requestId(), identity, ReadingResponse.class,
                () -> txService.addReading(equipmentId, req, identity));
    }

    public ReadingResponse addRawReading(String equipmentId, AddRawReadingRequest req) {
        WriteIdentity identity = idempotency.identity(OP_ADD_RAW_READING,
                "equipment/" + equipmentId + "/raw-readings/" + req.readingId(), req.operator(),
                orderedMap("equipmentId", equipmentId,
                        "readingId", req.readingId(),
                        "sampledAt", req.sampledAt(),
                        "raw", req.raw(),
                        "wrapCount", req.wrapCount(),
                        "expectedVersion", req.expectedVersion()));
        return recoverDuplicateKey(req.requestId(), identity, ReadingResponse.class,
                () -> txService.addRawReading(equipmentId, req, identity));
    }

    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        WriteIdentity identity = idempotency.identity(OP_REVISE_READING,
                "equipment/" + equipmentId + "/readings/" + readingId, req.operator(),
                orderedMap("equipmentId", equipmentId,
                        "readingId", readingId,
                        "cumulativeMinutes", req.cumulativeMinutes(),
                        "expectedVersion", req.expectedVersion()));
        return recoverDuplicateKey(req.requestId(), identity, ReadingResponse.class,
                () -> txService.reviseReading(equipmentId, readingId, req, identity));
    }

    public ReadingResponse reviseRawReading(String equipmentId, String readingId,
                                            ReviseRawReadingRequest req) {
        WriteIdentity identity = idempotency.identity(OP_REVISE_RAW_READING,
                "equipment/" + equipmentId + "/readings/" + readingId, req.operator(),
                orderedMap("equipmentId", equipmentId,
                        "readingId", readingId,
                        "readingRevision", req.readingRevision(),
                        "raw", req.raw(),
                        "wrapCount", req.wrapCount(),
                        "expectedVersion", req.expectedVersion()));
        return recoverDuplicateKey(req.requestId(), identity, ReadingResponse.class,
                () -> txService.reviseRawReading(equipmentId, readingId, req, identity));
    }

    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        WriteIdentity identity = idempotency.identity(OP_COMPLETE_MAINTENANCE,
                "equipment/" + equipmentId + "/maintenances", req.operator(),
                orderedMap("equipmentId", equipmentId,
                        "readingId", req.readingId(),
                        "anchorRevisionNo", req.anchorRevisionNo(),
                        "expectedVersion", req.expectedVersion()));
        return recoverDuplicateKey(req.requestId(), identity, MaintenanceResponse.class,
                () -> txService.completeMaintenance(equipmentId, req, identity));
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

    /** 保持插入顺序的参数表，保证指纹序列化确定性。 */
    private static Map<String, Object> orderedMap(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * 并发唯一键冲突补偿：冲突事务已回滚，若同 requestId 的成功记录已提交则重放（异参抛 409），
     * 否则说明是业务唯一键冲突，返回 409。
     */
    private <T> T recoverDuplicateKey(String requestId, WriteIdentity identity,
                                      Class<T> type, Supplier<T> action) {
        try {
            return action.get();
        } catch (DuplicateKeyException e) {
            T replayed = idempotency.replayExisting(requestId, identity.operation(),
                    identity.fingerprint(), type);
            if (replayed != null) {
                return replayed;
            }
            throw ApiException.conflict("DUPLICATE_KEY", "唯一约束冲突，请核对业务标识是否已被占用");
        }
    }
}
