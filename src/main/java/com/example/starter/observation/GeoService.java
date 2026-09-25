package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 观测坐标基准业务服务：观测提交（含坐标转换与簇归属）、设备基准变更与重算、
 * 冲突簇人工裁决及坐标/簇/重算记录查询。
 *
 * <p>坐标规则：观测提交时按提交的基准版本以公开的固定偏移参数转换为统一基准坐标；
 * 原始坐标与原基准版本不可改写。未知基准或经纬度越界（含转换后越界）返回 422。
 *
 * <p>簇规则：统一坐标球面距离不超过 50 米且采集时刻差不超过 60 秒的观测进入同簇
 * （边界精确包含，按连通分量归簇）；未人工裁决的簇取采集时刻最新的成员为当前胜出记录。
 *
 * <p>基准变更在同一事务内重算该设备所有未人工裁决观测的统一坐标与簇归属，
 * 任一转换失败整次回滚；重算改变簇成员或胜出记录时原子写入不可变重算记录；
 * 已人工裁决的簇保持其选择结论，不被自动覆盖。
 *
 * <p>并发与幂等：提交、基准变更与人工裁决均先写幂等占位记录再取簇操作全局锁，
 * 按提交顺序串行化；requestId 指纹含设备、基准版本、原始坐标、时刻和字段内容，
 * 同键同参重放首个结果，失败不占键。
 */
@Service
public class GeoService {

    private static final String SEPARATOR = "";

    /**
     * 同簇采集时刻差阈值：不超过该值才满足同簇时间条件，边界精确包含。
     */
    private static final Duration CLUSTER_TIME_WINDOW = Duration.ofSeconds(60);

    private final ObservationRepository observationRepository;
    private final ObservationGeoRepository geoRepository;
    private final DeviceFrameRepository deviceFrameRepository;
    private final ClusterRepository clusterRepository;
    private final FrameRecalcRepository frameRecalcRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;

    public GeoService(ObservationRepository observationRepository,
                      ObservationGeoRepository geoRepository,
                      DeviceFrameRepository deviceFrameRepository,
                      ClusterRepository clusterRepository,
                      FrameRecalcRepository frameRecalcRepository,
                      RequestLogRepository requestLogRepository,
                      ObjectMapper objectMapper) {
        this.observationRepository = observationRepository;
        this.geoRepository = geoRepository;
        this.deviceFrameRepository = deviceFrameRepository;
        this.clusterRepository = clusterRepository;
        this.frameRecalcRepository = frameRecalcRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 观测提交：创建观测记录（版本 1），按提交基准转换统一坐标并归簇。
     * 未知基准或经纬度越界返回 422；观测标识已存在返回 409。
     */
    @Transactional
    public Outcome<SubmissionResponse> submit(SubmitObservationRequest request) {
        String fingerprint = fingerprint("SUBMIT", request.observationId(), request.deviceId(),
                request.frameVersion(), Double.toString(request.latitude()),
                Double.toString(request.longitude()), request.capturedAt().toString(),
                request.location(), request.reading(), request.note());
        Outcome<SubmissionResponse> replayed = checkReplay(request.requestId(), fingerprint,
                SubmissionResponse.class);
        if (replayed != null) {
            return replayed;
        }
        Outcome<SubmissionResponse> concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "SUBMIT", SubmissionResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        FrameRegistry.FrameOffset offset = FrameRegistry.find(request.frameVersion())
                .orElseThrow(() -> ApiException.unprocessableEntity(
                        "unknown frame version: " + request.frameVersion()));
        if (!FrameRegistry.inRange(request.latitude(), request.longitude())) {
            throw ApiException.unprocessableEntity("latitude/longitude out of range: "
                    + request.latitude() + ", " + request.longitude());
        }
        double unifiedLatitude = offset.unifiedLatitude(request.latitude());
        double unifiedLongitude = offset.unifiedLongitude(request.longitude());
        if (!FrameRegistry.inRange(unifiedLatitude, unifiedLongitude)) {
            throw ApiException.unprocessableEntity(
                    "converted unified coordinates out of range for frame: " + request.frameVersion());
        }

        clusterRepository.acquireLock();
        // 首次提交自动登记设备基准；已登记设备不随提交改变基准版本
        deviceFrameRepository.insertIfAbsent(request.deviceId(), request.frameVersion());

        if (observationRepository.findCurrentForUpdate(request.observationId()).isPresent()) {
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        ObservationSnapshot snapshot = new ObservationSnapshot(request.observationId(), 1,
                request.location(), request.reading(), request.note(), false);
        try {
            observationRepository.insertCurrent(snapshot);
        } catch (DuplicateKeyException e) {
            // 并发提交同一 observationId：由主键串行化，后到者按冲突处理
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        observationRepository.insertVersion(snapshot);

        ObservationGeo geo = new ObservationGeo(request.observationId(), geoRepository.nextSeq(),
                request.deviceId(), request.frameVersion(), request.frameVersion(),
                request.latitude(), request.longitude(), unifiedLatitude, unifiedLongitude,
                request.capturedAt(), null);
        geoRepository.insert(geo);
        String clusterId = assignCluster(geo.observationId());

        ObservationGeo stored = geoRepository.findByObservationId(geo.observationId())
                .orElseThrow(() -> new IllegalStateException("geo missing after submit: "
                        + geo.observationId()));
        SubmissionResponse body = SubmissionResponse.of(snapshot,
                new ObservationGeo(stored.observationId(), stored.submissionSeq(), stored.deviceId(),
                        stored.originalFrameVersion(), stored.currentFrameVersion(),
                        stored.rawLatitude(), stored.rawLongitude(),
                        stored.unifiedLatitude(), stored.unifiedLongitude(),
                        stored.capturedAt(), clusterId));
        return complete(request.requestId(), 201, body);
    }

    /**
     * 设备基准版本登记/变更：变更时在同一事务内重算该设备所有未人工裁决观测的统一坐标与簇归属；
     * 任一转换失败（未知基准、转换后越界）整次回滚。重算改变簇成员或胜出记录时写入不可变重算记录。
     */
    @Transactional
    public Outcome<DeviceFrameResponse> updateDeviceFrame(String deviceId, UpdateDeviceFrameRequest request) {
        String fingerprint = fingerprint("FRAME", deviceId, request.frameVersion());
        Outcome<DeviceFrameResponse> replayed = checkReplay(request.requestId(), fingerprint,
                DeviceFrameResponse.class);
        if (replayed != null) {
            return replayed;
        }
        Outcome<DeviceFrameResponse> concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "FRAME", DeviceFrameResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        FrameRegistry.FrameOffset offset = FrameRegistry.find(request.frameVersion())
                .orElseThrow(() -> ApiException.unprocessableEntity(
                        "unknown frame version: " + request.frameVersion()));

        clusterRepository.acquireLock();
        DeviceFrame existing = deviceFrameRepository.findForUpdate(deviceId).orElse(null);
        String oldFrameVersion = existing == null ? null : existing.frameVersion();
        if (existing == null) {
            deviceFrameRepository.insert(deviceId, request.frameVersion());
        } else if (Objects.equals(oldFrameVersion, request.frameVersion())) {
            // 基准版本未变化：无需重算
            return complete(request.requestId(), 200,
                    new DeviceFrameResponse(deviceId, oldFrameVersion, request.frameVersion(), 0, null));
        } else {
            deviceFrameRepository.updateFrameVersion(deviceId, request.frameVersion());
        }

        List<ObservationGeo> deviceObservations = geoRepository.findByDeviceId(deviceId);
        Set<String> resolvedClusterIds = new HashSet<>(clusterRepository.findManuallyResolvedIds());
        // 已人工裁决簇中的观测不参与重算，保持其选择结论
        List<ObservationGeo> affected = deviceObservations.stream()
                .filter(geo -> geo.clusterId() == null || !resolvedClusterIds.contains(geo.clusterId()))
                .sorted(Comparator.comparingLong(ObservationGeo::submissionSeq))
                .toList();
        List<ClusterSnapshot> oldSnapshots = snapshotsOfDeviceClusters(deviceId);

        int recalculated = 0;
        if (!affected.isEmpty()) {
            // 先完成全部转换校验：任一失败抛 422，整次事务回滚
            List<ConvertedCoordinate> conversions = new ArrayList<>();
            for (ObservationGeo geo : affected) {
                double unifiedLatitude = offset.unifiedLatitude(geo.rawLatitude());
                double unifiedLongitude = offset.unifiedLongitude(geo.rawLongitude());
                if (!FrameRegistry.inRange(unifiedLatitude, unifiedLongitude)) {
                    throw ApiException.unprocessableEntity(
                            "converted unified coordinates out of range for observation: "
                                    + geo.observationId() + " under frame: " + request.frameVersion());
                }
                conversions.add(new ConvertedCoordinate(geo.observationId(),
                        unifiedLatitude, unifiedLongitude));
            }
            // 同一事务内：先脱离原簇，再按新统一坐标更新，最后按提交顺序重新归簇
            for (ConvertedCoordinate conversion : conversions) {
                geoRepository.updateClusterId(conversion.observationId(), null);
                geoRepository.updateUnified(conversion.observationId(), request.frameVersion(),
                        conversion.unifiedLatitude(), conversion.unifiedLongitude());
            }
            recalculated = conversions.size();
            for (ConvertedCoordinate conversion : conversions) {
                assignCluster(conversion.observationId());
            }
            cleanupClusters();
        }

        List<ClusterSnapshot> newSnapshots = snapshotsOfDeviceClusters(deviceId);
        String recalcId = null;
        if (!oldSnapshots.equals(newSnapshots)) {
            // 重算改变了簇成员或当前胜出记录：固化新旧簇和参数版本
            recalcId = request.requestId();
            frameRecalcRepository.insert(new FrameRecalcRecord(recalcId, deviceId, request.requestId(),
                    oldFrameVersion, request.frameVersion(), oldSnapshots, newSnapshots));
        }
        return complete(request.requestId(), 200,
                new DeviceFrameResponse(deviceId, oldFrameVersion, request.frameVersion(),
                        recalculated, recalcId));
    }

    /**
     * 冲突簇人工裁决：选定簇内一条观测记录作为胜出记录并固化，之后不被自动覆盖。
     * 已裁决簇对相同选择幂等成功，对不同选择返回 409。
     */
    @Transactional
    public Outcome<ClusterResponse> resolveCluster(String clusterId, ResolveClusterRequest request) {
        String fingerprint = fingerprint("ADJUDICATE", clusterId, request.observationId(), request.operator());
        Outcome<ClusterResponse> replayed = checkReplay(request.requestId(), fingerprint,
                ClusterResponse.class);
        if (replayed != null) {
            return replayed;
        }
        Outcome<ClusterResponse> concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "ADJUDICATE", ClusterResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        clusterRepository.acquireLock();
        ConflictCluster cluster = clusterRepository.findForUpdate(clusterId)
                .orElseThrow(() -> ApiException.notFound("cluster not found: " + clusterId));
        List<ObservationGeo> members = geoRepository.findByClusterId(clusterId);
        boolean isMember = members.stream()
                .anyMatch(geo -> geo.observationId().equals(request.observationId()));
        if (!isMember) {
            throw ApiException.badRequest(
                    "observation is not a member of cluster " + clusterId + ": " + request.observationId());
        }

        ConflictCluster result = cluster;
        if (cluster.manuallyResolved()) {
            if (!Objects.equals(cluster.resolvedObservationId(), request.observationId())) {
                throw ApiException.conflict(
                        "cluster already manually resolved with a different observation: " + clusterId, null);
            }
            // 相同选择：幂等成功，不重复变更
        } else {
            clusterRepository.markResolved(clusterId, request.observationId());
            result = clusterRepository.findById(clusterId)
                    .orElseThrow(() -> new IllegalStateException("cluster missing after resolve: " + clusterId));
        }
        return complete(request.requestId(), 200, ClusterResponse.of(result,
                members.stream().map(ObservationGeo::observationId).toList()));
    }

    /**
     * 查询观测的原始与统一坐标；无坐标信息的观测返回 404。
     */
    @Transactional(readOnly = true)
    public CoordinatesResponse getCoordinates(String observationId) {
        ObservationGeo geo = geoRepository.findByObservationId(observationId)
                .orElseThrow(() -> ApiException.notFound("coordinates not found: " + observationId));
        return CoordinatesResponse.of(geo);
    }

    /**
     * 查询冲突簇；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ClusterResponse getCluster(String clusterId) {
        ConflictCluster cluster = clusterRepository.findById(clusterId)
                .orElseThrow(() -> ApiException.notFound("cluster not found: " + clusterId));
        List<String> members = geoRepository.findByClusterId(clusterId).stream()
                .map(ObservationGeo::observationId)
                .toList();
        return ClusterResponse.of(cluster, members);
    }

    /**
     * 按设备查询基准重算记录列表，按落库先后排序。
     */
    @Transactional(readOnly = true)
    public List<FrameRecalcResponse> listRecalcs(String deviceId) {
        return frameRecalcRepository.findByDeviceId(deviceId).stream()
                .map(FrameRecalcResponse::of)
                .toList();
    }

    /**
     * 按标识查询基准重算记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public FrameRecalcResponse getRecalc(String recalcId) {
        return frameRecalcRepository.findById(recalcId)
                .map(FrameRecalcResponse::of)
                .orElseThrow(() -> ApiException.notFound("frame recalc not found: " + recalcId));
    }

    // ---------- 簇归属计算 ----------

    /**
     * 计算目标观测的簇归属：以其统一坐标与采集时刻求连通分量，分量大于一时
     * 将全部成员并入存活簇（已有人工裁决簇优先存活，其次取既有簇标识最小者，否则新建簇），
     * 并重算未人工裁决簇的胜出记录。返回所属簇标识；无冲突时返回 null。
     */
    private String assignCluster(String observationId) {
        List<ObservationGeo> all = geoRepository.findAll();
        Set<String> component = connectedComponent(all, observationId);
        if (component.size() <= 1) {
            return null;
        }
        Map<String, ConflictCluster> involved = new HashMap<>();
        for (ObservationGeo geo : all) {
            if (component.contains(geo.observationId()) && geo.clusterId() != null) {
                clusterRepository.findById(geo.clusterId())
                        .ifPresent(cluster -> involved.put(cluster.clusterId(), cluster));
            }
        }
        String survivor = involved.values().stream()
                .filter(ConflictCluster::manuallyResolved)
                .map(ConflictCluster::clusterId)
                .min(Comparator.naturalOrder())
                .orElseGet(() -> involved.keySet().stream().min(Comparator.naturalOrder())
                        .orElseGet(() -> "clu-" + UUID.randomUUID()));
        if (!involved.containsKey(survivor)) {
            clusterRepository.insert(survivor);
        }
        for (String memberId : component) {
            geoRepository.updateClusterId(memberId, survivor);
        }
        for (String clusterId : involved.keySet()) {
            if (!clusterId.equals(survivor)) {
                clusterRepository.delete(clusterId);
            }
        }
        ConflictCluster survivorCluster = clusterRepository.findById(survivor)
                .orElseThrow(() -> new IllegalStateException("cluster missing after assign: " + survivor));
        if (!survivorCluster.manuallyResolved()) {
            recomputeWinner(survivor);
        }
        return survivor;
    }

    /**
     * 以目标观测为起点做广度优先搜索，返回满足同簇条件（距离与时刻差均在阈值内）的连通分量。
     */
    private Set<String> connectedComponent(List<ObservationGeo> all, String startObservationId) {
        Map<String, ObservationGeo> byId = new HashMap<>();
        for (ObservationGeo geo : all) {
            byId.put(geo.observationId(), geo);
        }
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        visited.add(startObservationId);
        queue.add(startObservationId);
        while (!queue.isEmpty()) {
            ObservationGeo current = byId.get(queue.poll());
            for (ObservationGeo other : all) {
                if (visited.contains(other.observationId())) {
                    continue;
                }
                if (inSameCluster(current, other)) {
                    visited.add(other.observationId());
                    queue.add(other.observationId());
                }
            }
        }
        return visited;
    }

    /**
     * 同簇判定：统一坐标球面距离不超过 50 米且采集时刻差不超过 60 秒，边界精确包含。
     */
    private boolean inSameCluster(ObservationGeo left, ObservationGeo right) {
        if (!SphericalDistance.withinClusterRadius(left.unifiedLatitude(), left.unifiedLongitude(),
                right.unifiedLatitude(), right.unifiedLongitude())) {
            return false;
        }
        Duration gap = Duration.between(left.capturedAt(), right.capturedAt()).abs();
        return gap.compareTo(CLUSTER_TIME_WINDOW) <= 0;
    }

    /**
     * 重算未人工裁决簇的胜出记录：采集时刻最新者胜出，时刻相同取提交顺序号较大者。
     */
    private void recomputeWinner(String clusterId) {
        List<ObservationGeo> members = geoRepository.findByClusterId(clusterId);
        ObservationGeo winner = members.stream()
                .max(Comparator.comparing(ObservationGeo::capturedAt)
                        .thenComparingLong(ObservationGeo::submissionSeq))
                .orElseThrow(() -> new IllegalStateException("cluster has no members: " + clusterId));
        clusterRepository.updateWinner(clusterId, winner.observationId());
    }

    /**
     * 簇清理（基准重算后）：空簇删除；仅剩一名成员的未人工裁决簇解散；其余未人工裁决簇重算胜出记录。
     * 已人工裁决的簇保持其选择结论，不做任何改动。
     */
    private void cleanupClusters() {
        for (ConflictCluster cluster : clusterRepository.findAll()) {
            if (cluster.manuallyResolved()) {
                continue;
            }
            List<ObservationGeo> members = geoRepository.findByClusterId(cluster.clusterId());
            if (members.isEmpty()) {
                clusterRepository.delete(cluster.clusterId());
            } else if (members.size() == 1) {
                geoRepository.updateClusterId(members.get(0).observationId(), null);
                clusterRepository.delete(cluster.clusterId());
            } else {
                recomputeWinner(cluster.clusterId());
            }
        }
    }

    /**
     * 设备相关簇快照：包含该设备任一观测的所有簇，固化成员、胜出记录与裁决状态，按簇标识排序。
     */
    private List<ClusterSnapshot> snapshotsOfDeviceClusters(String deviceId) {
        Set<String> clusterIds = new TreeSet<>();
        for (ObservationGeo geo : geoRepository.findByDeviceId(deviceId)) {
            if (geo.clusterId() != null) {
                clusterIds.add(geo.clusterId());
            }
        }
        List<ClusterSnapshot> snapshots = new ArrayList<>();
        for (String clusterId : clusterIds) {
            ConflictCluster cluster = clusterRepository.findById(clusterId)
                    .orElseThrow(() -> new IllegalStateException("cluster missing: " + clusterId));
            List<String> members = geoRepository.findByClusterId(clusterId).stream()
                    .map(ObservationGeo::observationId)
                    .sorted()
                    .toList();
            snapshots.add(new ClusterSnapshot(clusterId, members,
                    cluster.winnerObservationId(), cluster.manuallyResolved()));
        }
        return snapshots;
    }

    // ---------- 幂等与序列化辅助 ----------

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private <T> Outcome<T> checkReplay(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new Outcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    /**
     * 占位写入去重记录；并发同键时主键冲突，等待对方事务结束后读取已提交结果：
     * 同参返回重放结果，异参抛 409；正常占位返回 null。业务失败时占位随事务回滚，不占键。
     */
    private <T> Outcome<T> insertPlaceholder(String requestId, String fingerprint, String operation,
                                             Class<T> bodyType) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new Outcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
        }
    }

    /**
     * 业务成功后回填去重记录响应，并构造本次写操作结果；与业务变更同事务提交。
     */
    private <T> Outcome<T> complete(String requestId, int status, T body) {
        requestLogRepository.complete(requestId, status, writeBody(body));
        return new Outcome<>(status, body);
    }

    private String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    private <T> T readBody(String json, Class<T> bodyType) {
        try {
            return objectMapper.readValue(json, bodyType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 基准重算中单条观测的转换结果。
     */
    private record ConvertedCoordinate(String observationId,
                                       double unifiedLatitude,
                                       double unifiedLongitude) {
    }

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record Outcome<T>(int status, T body) {
    }
}
