package com.example.starter.maintenance.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddCycleRequest;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.CycleResponse;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 设备工时保养外观服务：委托事务服务执行；并发下唯一键冲突（事务已回滚）时，
 * 优先按 requestId 重放已提交的成功结果，否则转换为 409 业务冲突。
 */
@Service
public class EquipmentService {

    private final EquipmentTxService txService;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public EquipmentService(EquipmentTxService txService, IdempotencyService idempotency,
                            ObjectMapper objectMapper) {
        this.txService = txService;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    public EquipmentResponse register(RegisterEquipmentRequest req) {
        String fingerprint = req.equipmentId() + "|" + req.maintenancePeriodMinutes()
                + "|" + req.fatigueThreshold() + "|" + req.baselineStressMpa();
        return recoverDuplicateKey(req.requestId(), "REGISTER_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> txService.register(req));
    }

    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        return txService.addReading(equipmentId, req);
    }

    /**
     * 新增闭合应力循环。结构化身份（操作、资源、全参数、期望版本）在此生成并贯穿幂等判定；
     * 并发唯一键冲突时按 requestId 重放已提交结果，不泄漏唯一键异常。
     */
    public CycleResponse addCycle(String equipmentId, AddCycleRequest req) {
        String fingerprint = cycleFingerprint(equipmentId, req);
        return recoverDuplicateKey(req.requestId(), "ADD_STRESS_CYCLE", fingerprint,
                CycleResponse.class, () -> txService.addCycle(equipmentId, req, fingerprint));
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

    public List<CycleResponse> listCycles(String equipmentId) {
        return txService.listCycles(equipmentId);
    }

    /**
     * 应力循环的结构化身份：操作、资源、全参数及期望版本。基于解析后的值序列化，
     * 十进制同值（如 10 与 10.0）指纹相同，文本原样参与。
     */
    private String cycleFingerprint(String equipmentId, AddCycleRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", "ADD_STRESS_CYCLE");
        identity.put("resource", "/api/equipment/" + equipmentId + "/cycles");
        identity.put("expectedVersion", req.expectedVersion());
        identity.put("cycleId", req.cycleId());
        identity.put("operator", req.operator());
        identity.put("rise", sampleIdentity(req.rise()));
        identity.put("fall", req.fall() == null ? null : sampleIdentity(req.fall()));
        try {
            return objectMapper.writeValueAsString(identity);
        } catch (Exception e) {
            throw new IllegalStateException("结构化身份序列化失败", e);
        }
    }

    private Map<String, Object> sampleIdentity(AddCycleRequest.Sample sample) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stressMpa", sample.stressMpa());
        map.put("sampledAt", sample.sampledAt().toString());
        map.put("readingId", sample.readingId());
        map.put("readingRevisionNo", sample.readingRevisionNo());
        return map;
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
