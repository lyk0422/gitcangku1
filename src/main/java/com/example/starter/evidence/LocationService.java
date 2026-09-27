package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.LocationCreateRequest;
import com.example.starter.evidence.dto.LocationInventoryView;
import com.example.starter.evidence.dto.LocationView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 库位管理服务。创建与停用为幂等命令（commandKey），写操作在单事务内完成；
 * 停用只影响后续迁移与放入，历史库存与迁移记录不改写。
 */
@Service
public class LocationService {

    static final String OP_LOCATION_CREATE = "LOCATION_CREATE";
    static final String OP_LOCATION_DISABLE = "LOCATION_DISABLE";

    private final StorageLocationRepository locationRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public LocationService(StorageLocationRepository locationRepository,
                           EvidenceRepository evidenceRepository,
                           CommandLogRepository commandLogRepository,
                           ObjectMapper objectMapper) {
        this.locationRepository = locationRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建库位：初始 ACTIVE、库存版本 0。locationCode 全局唯一。
     */
    @Transactional
    public StoredResponse create(String actorId, LocationCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (locationRepository.findByCode(request.locationCode()).isPresent()) {
            throw ApiException.conflict("库位已存在: " + request.locationCode());
        }
        LocalDateTime now = LocalDateTime.now();
        locationRepository.insert(request.locationCode(), request.label(), now);
        StorageLocation location = locationRepository.findByCode(request.locationCode()).orElseThrow();
        return record(request.commandKey(), OP_LOCATION_CREATE, actorId, requestHash,
                201, toView(location));
    }

    /**
     * 停用库位：仅 ACTIVE 可停用；停用后不可作为迁移目标或入库位目标。
     */
    @Transactional
    public StoredResponse disable(String actorId, String locationCode,
                                  CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        StorageLocation location = locationRepository.findByCodeForUpdate(locationCode)
                .orElseThrow(() -> ApiException.notFound("库位不存在: " + locationCode));
        // 并发下本事务可能在库位行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (location.status() == LocationStatus.DISABLED) {
            throw ApiException.conflict("库位已停用: " + locationCode);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!locationRepository.disable(locationCode, now)) {
            throw ApiException.conflict("库位已被并发停用: " + locationCode);
        }
        StorageLocation updated = locationRepository.findByCode(locationCode).orElseThrow();
        return record(request.commandKey(), OP_LOCATION_DISABLE, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 查询库位库存：库位状态、库存版本与当前在库证物列表。
     */
    @Transactional(readOnly = true)
    public LocationInventoryView inventory(String locationCode) {
        StorageLocation location = locationRepository.findByCode(locationCode)
                .orElseThrow(() -> ApiException.notFound("库位不存在: " + locationCode));
        List<EvidenceView> evidence = evidenceRepository.findByLocationCode(locationCode)
                .stream().map(this::toView).toList();
        return new LocationInventoryView(location.locationCode(), location.label(),
                location.status(), location.version(), evidence);
    }

    private Optional<StoredResponse> checkReplay(String commandKey, String requestHash) {
        return commandLogRepository.findByKey(commandKey).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String commandKey, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(commandKey, actorId, operation, requestHash, status, json,
                LocalDateTime.now());
        return new StoredResponse(status, json);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private LocationView toView(StorageLocation location) {
        return new LocationView(location.locationCode(), location.label(), location.status(),
                location.version(), location.createdAt(), location.updatedAt());
    }

    private EvidenceView toView(Evidence evidence) {
        return new EvidenceView(evidence.evidenceKey(), evidence.caseKey(), evidence.category(),
                evidence.sealNo(), evidence.custodianId(), evidence.status(),
                evidence.locationCode(), evidence.createdAt(), evidence.updatedAt());
    }
}
