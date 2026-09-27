package com.example.starter.plan.service;

import com.example.starter.plan.model.PublishedStationStop;
import com.example.starter.plan.model.StationConfig;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.StationRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.StationConfigRequest;
import com.example.starter.plan.web.dto.StationConfigResponse;
import com.example.starter.plan.web.dto.StationDiagnosticsResponse;
import com.example.starter.plan.web.dto.StationSnapshotView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 中间站配置与站停查询业务：站点规则登记/修订（版本按事务提交顺序裁决）、
 * 发布快照历史与站台容量诊断。所有查询只读，不改变状态，排序稳定。
 *
 * <p>幂等约定与计划写操作一致：指纹含操作者、操作类型、规范化字段（规范化 JSON）；
 * 同键同参重放返回首次成功响应快照，同键异参 409，失败不占键。
 */
@Service
public class StationService {

    private static final String OP_STATION_CONFIG = "STATION_CONFIG";

    private final StationRepository stationRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final RequestFingerprint fingerprint;
    private final TransactionTemplate tx;

    public StationService(StationRepository stationRepo, IdempotencyRepository idemRepo,
                          ObjectMapper objectMapper, RequestFingerprint fingerprint,
                          PlatformTransactionManager txManager) {
        this.stationRepo = stationRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.fingerprint = fingerprint;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 登记新站点（expectedVersion 为 null，初始版本 1）或修订现有站点
     * （expectedVersion 必须等于当前版本，成功后版本加一）。
     */
    public StationConfigResponse saveConfig(String stationId, StationConfigRequest req) {
        String hash = hashConfig(stationId, req);
        Optional<StationConfigResponse> replay =
                replayIfPresent(req.requestKey(), hash, StationConfigResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                Optional<StationConfig> existing = stationRepo.findByStationIdForUpdate(stationId);
                long now = System.currentTimeMillis();
                StationConfigResponse response;
                if (existing.isEmpty()) {
                    if (req.expectedVersion() != null) {
                        throw notFound(stationId);
                    }
                    long id = stationRepo.insertStationConfig(stationId,
                            req.minDwellMinutes(), req.platformCapacity(), now);
                    response = toView(new StationConfig(id, stationId, 1,
                            req.minDwellMinutes(), req.platformCapacity(), now, now));
                } else {
                    StationConfig current = existing.get();
                    if (req.expectedVersion() == null) {
                        throw conflict("STATION_EXISTS",
                                "站点已存在，修订须携带 expectedVersion: " + stationId);
                    }
                    if (req.expectedVersion() != current.version()) {
                        throw conflict("VERSION_CONFLICT",
                                "expectedVersion=" + req.expectedVersion()
                                        + " 与当前版本 " + current.version() + " 不一致");
                    }
                    int newVersion = current.version() + 1;
                    stationRepo.updateStationConfig(current.id(), newVersion,
                            req.minDwellMinutes(), req.platformCapacity(), now);
                    response = toView(new StationConfig(current.id(), stationId, newVersion,
                            req.minDwellMinutes(), req.platformCapacity(),
                            current.createdAt(), now));
                }
                idemRepo.insert(OP_STATION_CONFIG, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(req.requestKey(), hash, StationConfigResponse.class)
                    .orElseThrow(() -> conflict("STATION_EXISTS", "站点已存在: " + stationId));
        }
    }

    /**
     * 查询站点当前配置，不存在返回 404。只读。
     */
    public StationConfigResponse getConfig(String stationId) {
        return stationRepo.findByStationId(stationId)
                .map(this::toView)
                .orElseThrow(() -> notFound(stationId));
    }

    /**
     * 查询站点发布快照历史（含已取消计划的历史快照，快照本身不可变）；
     * 可按运营日过滤。排序：运营日、到达时刻（NULL 最后）、计划键、序号。只读。
     */
    public List<StationSnapshotView> getSnapshotHistory(String stationId, LocalDate opDate) {
        if (stationRepo.findByStationId(stationId).isEmpty()) {
            throw notFound(stationId);
        }
        return stationRepo.findSnapshotHistory(stationId, opDate).stream()
                .map(row -> toSnapshotView(row.scheduleKey(), row.stop()))
                .toList();
    }

    /**
     * 站台容量诊断：统计指定运营日当前仍生效（PUBLISHED）计划在该站的同时占用峰值，
     * 返回实际峰值、容量要求与差额，以及参与统计的快照明细。只读。
     */
    public StationDiagnosticsResponse getDiagnostics(String stationId, LocalDate date) {
        StationConfig config = stationRepo.findByStationId(stationId)
                .orElseThrow(() -> notFound(stationId));
        Instant dayStart = date.atStartOfDay(PlanService.OPERATION_ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(PlanService.OPERATION_ZONE).toInstant();

        List<StationRepository.SnapshotRow> active =
                stationRepo.findActiveSnapshots(date, List.of(stationId), List.of());
        List<StationCapacityEvaluator.PlatformInterval> intervals = new ArrayList<>();
        for (StationRepository.SnapshotRow row : active) {
            PublishedStationStop stop = row.stop();
            Instant start = stop.arrivalUtc() != null ? stop.arrivalUtc() : dayStart;
            Instant end = stop.departureUtc() != null ? stop.departureUtc() : dayEnd;
            if (start.isBefore(end)) {
                intervals.add(new StationCapacityEvaluator.PlatformInterval(
                        stationId, row.scheduleKey(), stop.trainNo(), stop.seq(), start, end));
            }
        }
        Map<String, StationCapacityEvaluator.StationResult> results =
                StationCapacityEvaluator.evaluate(intervals,
                        Map.of(stationId, config.platformCapacity()));
        StationCapacityEvaluator.StationResult result =
                results.getOrDefault(stationId,
                        new StationCapacityEvaluator.StationResult(0, List.of()));

        List<StationSnapshotView> snapshots = active.stream()
                .map(row -> toSnapshotView(row.scheduleKey(), row.stop()))
                .toList();
        return new StationDiagnosticsResponse(stationId, date, config.version(),
                config.minDwellMinutes(), config.platformCapacity(), active.size(),
                result.peakConcurrent(), config.platformCapacity() - result.peakConcurrent(),
                snapshots);
    }

    private StationConfigResponse toView(StationConfig config) {
        return new StationConfigResponse(config.stationId(), config.version(),
                config.minDwellMinutes(), config.platformCapacity(),
                config.createdAt(), config.updatedAt());
    }

    private StationSnapshotView toSnapshotView(String scheduleKey, PublishedStationStop stop) {
        return new StationSnapshotView(scheduleKey, stop.seq(), stop.trainNo(), stop.stationId(),
                stop.stationVersion(), stop.arrivalUtc(), stop.departureUtc(),
                stop.minDwellMinutes(), stop.platformCapacity());
    }

    private String hashConfig(String stationId, StationConfigRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stationId", stationId);
        payload.put("minDwellMinutes", req.minDwellMinutes());
        payload.put("platformCapacity", req.platformCapacity());
        payload.put("expectedVersion", req.expectedVersion());
        return fingerprint.sha256(OP_STATION_CONFIG, req.operator(), payload);
    }

    private <T> Optional<T> replayIfPresent(String requestKey, String hash, Class<T> type) {
        return idemRepo.find(OP_STATION_CONFIG, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException notFound(String stationId) {
        return new ApiException(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND",
                "站点不存在: " + stationId);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
