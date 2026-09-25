package com.example.starter.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 观测坐标基准转换与冲突半径重算业务服务。
 *
 * <p>所有坐标写操作（基准登记/设备基准修改/观测提交/人工裁决）均在单事务内完成：
 * 先占位写入幂等去重记录，再获取全局行锁（geo_lock 唯一行）按提交顺序串行裁决，
 * 任何业务失败整次回滚，去重记录不占键。
 *
 * <p>坐标换算：服务端按基准版本登记的公开固定偏移参数（纬度/经度加法偏移）换算统一基准坐标，
 * 原始坐标与原基准版本同时落库且不可改写；未知基准、原始或换算后经纬度越界返回 422。
 *
 * <p>冲突簇：统一坐标球面距离小于等于 50 米且采集时刻差不超过 60 秒进入同簇（边界精确包含），
 * 同簇关系按观测对传递。设备基准修改后在同一事务内重算该设备全部观测的统一坐标，
 * 并重算所有未人工裁决簇；已人工裁决簇的成员与胜出结论冻结，不被自动覆盖。
 * 重算改变簇成员或当前胜出记录时写入不可变基准重算记录。
 */
@Service
public class GeoObservationService {

    private static final String SEPARATOR = "";
    private static final String OP_FRAME_REGISTER = "FRAME_REGISTER";
    private static final String OP_DEVICE_FRAME = "DEVICE_FRAME";
    private static final String OP_GEO_SUBMIT = "GEO_SUBMIT";
    private static final String OP_CLUSTER_RESOLVE = "CLUSTER_RESOLVE";
    private static final TypeReference<List<ClusterSnapshot>> SNAPSHOT_LIST_TYPE = new TypeReference<>() {
    };

    private final CoordinateFrameRepository frameRepository;
    private final DeviceFrameRepository deviceFrameRepository;
    private final GeoObservationRepository observationRepository;
    private final GeoClusterRepository clusterRepository;
    private final FrameRecalcRepository recalcRepository;
    private final RequestLogRepository requestLogRepository;
    private final GeoLockRepository lockRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public GeoObservationService(CoordinateFrameRepository frameRepository,
                                 DeviceFrameRepository deviceFrameRepository,
                                 GeoObservationRepository observationRepository,
                                 GeoClusterRepository clusterRepository,
                                 FrameRecalcRepository recalcRepository,
                                 RequestLogRepository requestLogRepository,
                                 GeoLockRepository lockRepository,
                                 ObjectMapper objectMapper,
                                 Clock clock) {
        this.frameRepository = frameRepository;
        this.deviceFrameRepository = deviceFrameRepository;
        this.observationRepository = observationRepository;
        this.clusterRepository = clusterRepository;
        this.recalcRepository = recalcRepository;
        this.requestLogRepository = requestLogRepository;
        this.lockRepository = lockRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 基准登记 ----------

    /**
     * 登记坐标基准版本（公开固定偏移参数）；版本已存在返回 409。
     */
    @Transactional
    public TypedOutcome<FrameResponse> registerFrame(RegisterFrameRequest request) {
        String fingerprint = fingerprint(OP_FRAME_REGISTER, request.frameVersion(),
                canonical(request.offsetLatDeg()), canonical(request.offsetLonDeg()));
        TypedOutcome<FrameResponse> replayed = checkReplay(request.requestId(), fingerprint, FrameResponse.class);
        if (replayed != null) {
            return replayed;
        }
        TypedOutcome<FrameResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, OP_FRAME_REGISTER, FrameResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        CoordinateFrame frame = new CoordinateFrame(
                request.frameVersion(), request.offsetLatDeg(), request.offsetLonDeg());
        try {
            frameRepository.insert(frame);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("coordinate frame already exists: " + request.frameVersion(), null);
        }
        return complete(request.requestId(), HttpStatus.CREATED, FrameResponse.of(frame));
    }

    // ---------- 设备基准修改与重算 ----------

    /**
     * 修改设备坐标基准版本，并在同一事务内重算该设备所有未人工裁决观测的统一坐标与簇归属；
     * 任一转换失败整次回滚。目标基准未登记返回 422。
     */
    @Transactional
    public TypedOutcome<DeviceFrameResponse> updateDeviceFrame(String deviceId, UpdateDeviceFrameRequest request) {
        String fingerprint = fingerprint(OP_DEVICE_FRAME, deviceId, request.frameVersion());
        TypedOutcome<DeviceFrameResponse> replayed =
                checkReplay(request.requestId(), fingerprint, DeviceFrameResponse.class);
        if (replayed != null) {
            return replayed;
        }
        TypedOutcome<DeviceFrameResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, OP_DEVICE_FRAME, DeviceFrameResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        lockRepository.lockGlobal();
        CoordinateFrame newFrame = frameRepository.find(request.frameVersion())
                .orElseThrow(() -> ApiException.unprocessableEntity(
                        "unknown coordinate frame: " + request.frameVersion()));
        Optional<String> previous = deviceFrameRepository.findFrameVersionForUpdate(deviceId);
        String oldFrameVersion = previous.orElse(null);

        if (request.frameVersion().equals(oldFrameVersion)) {
            // 基准版本未变化：不构成修改，不触发重算与重算记录。
            return complete(request.requestId(), HttpStatus.OK,
                    new DeviceFrameResponse(deviceId, request.frameVersion(), false, null));
        }

        List<ClusterSnapshot> oldSnapshots = autoClusterSnapshots();

        // 用新基准参数重算该设备未人工裁决观测的统一坐标；原始坐标与原基准版本不变。
        // 已人工裁决簇的成员冻结，不参与重算。
        // 任一观测换算结果越界即抛 422，整次事务回滚：设备基准版本与已重算坐标均不落库。
        Set<String> frozenObservationIds = frozenObservationIds();
        List<GeoObservation> deviceObservations = observationRepository.findByDeviceId(deviceId);
        for (GeoObservation observation : deviceObservations) {
            if (frozenObservationIds.contains(observation.observationId())) {
                continue;
            }
            double[] unified = convertAndValidate(observation.rawLatitude(), observation.rawLongitude(), newFrame);
            observationRepository.updateUnified(
                    observation.observationId(), unified[0], unified[1], newFrame.frameVersion());
        }

        List<ClusterSnapshot> newSnapshots = rebuildAutoClusters();
        boolean changed = !oldSnapshots.equals(newSnapshots);
        String recalcId = null;
        if (changed) {
            recalcId = "FR-" + UUID.randomUUID().toString().replace("-", "");
            FrameRecalcRecord record = new FrameRecalcRecord(
                    recalcId, deviceId, oldFrameVersion, newFrame.frameVersion(),
                    writeJson(oldSnapshots), writeJson(newSnapshots), Instant.now(clock));
            recalcRepository.insert(record);
        }

        if (previous.isPresent()) {
            deviceFrameRepository.update(deviceId, newFrame.frameVersion());
        } else {
            deviceFrameRepository.insert(deviceId, newFrame.frameVersion());
        }
        return complete(request.requestId(), HttpStatus.OK,
                new DeviceFrameResponse(deviceId, newFrame.frameVersion(), changed, recalcId));
    }

    // ---------- 观测提交 ----------

    /**
     * 提交带坐标观测：按所携带基准版本的固定偏移换算统一坐标并落库（原始坐标不可改写），
     * 随后在全局串行化下重算自动簇归属。未知基准或经纬度越界返回 422。
     */
    @Transactional
    public TypedOutcome<GeoObservationResponse> submit(String deviceId, SubmitGeoObservationRequest request) {
        Instant capturedAt = parseCapturedAt(request.capturedAt());
        String fingerprint = fingerprint(OP_GEO_SUBMIT,
                deviceId, request.frameVersion(),
                canonical(request.latitude()), canonical(request.longitude()),
                request.capturedAt(), request.location(), request.reading(), request.note());
        TypedOutcome<GeoObservationResponse> replayed =
                checkReplay(request.requestId(), fingerprint, GeoObservationResponse.class);
        if (replayed != null) {
            return replayed;
        }
        TypedOutcome<GeoObservationResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, OP_GEO_SUBMIT, GeoObservationResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        lockRepository.lockGlobal();
        CoordinateFrame frame = frameRepository.find(request.frameVersion())
                .orElseThrow(() -> ApiException.unprocessableEntity(
                        "unknown coordinate frame: " + request.frameVersion()));
        validateRawCoordinates(request.latitude(), request.longitude());
        double[] unified = convertAndValidate(request.latitude(), request.longitude(), frame);

        String observationId = "GO-" + UUID.randomUUID().toString().replace("-", "");
        GeoObservation observation = new GeoObservation(
                observationId, deviceId, request.requestId(),
                request.latitude(), request.longitude(), request.frameVersion(),
                unified[0], unified[1], frame.frameVersion(), capturedAt,
                request.location(), request.reading(), request.note(), null);
        observationRepository.insert(observation);

        rebuildAutoClusters();
        GeoObservation stored = observationRepository.find(observationId)
                .orElseThrow(() -> new IllegalStateException("inserted observation missing: " + observationId));
        return complete(request.requestId(), HttpStatus.CREATED, GeoObservationResponse.of(stored));
    }

    // ---------- 人工裁决 ----------

    /**
     * 冲突簇人工裁决：胜出观测必须是该簇当前成员；已裁决簇的选择结论锁定，拒绝改判（409）。
     */
    @Transactional
    public TypedOutcome<ClusterResponse> resolveCluster(String clusterId, ResolveClusterRequest request) {
        String fingerprint = fingerprint(OP_CLUSTER_RESOLVE,
                clusterId, request.winnerObservationId(), request.operator());
        TypedOutcome<ClusterResponse> replayed =
                checkReplay(request.requestId(), fingerprint, ClusterResponse.class);
        if (replayed != null) {
            return replayed;
        }
        TypedOutcome<ClusterResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, OP_CLUSTER_RESOLVE, ClusterResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        lockRepository.lockGlobal();
        GeoCluster cluster = clusterRepository.findForUpdate(clusterId)
                .orElseThrow(() -> ApiException.notFound("cluster not found: " + clusterId));
        List<String> memberIds = observationRepository.findMemberIds(clusterId);
        if (cluster.manuallyResolved()) {
            // 已人工解决的簇保持其选择结论，不被自动或重复请求覆盖。
            throw ApiException.conflict("cluster already manually resolved: " + clusterId, null);
        }
        if (!memberIds.contains(request.winnerObservationId())) {
            throw ApiException.unprocessableEntity(
                    "winner observation is not a member of cluster " + clusterId + ": "
                            + request.winnerObservationId());
        }
        clusterRepository.markResolved(clusterId, request.winnerObservationId(),
                request.operator(), Instant.now(clock));
        GeoCluster resolved = clusterRepository.find(clusterId)
                .orElseThrow(() -> new IllegalStateException("resolved cluster missing: " + clusterId));
        return complete(request.requestId(), HttpStatus.OK, ClusterResponse.of(resolved, memberIds));
    }

    // ---------- 查询 ----------

    /**
     * 查询坐标观测：原始坐标、统一坐标与簇归属一并返回；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public GeoObservationResponse getObservation(String observationId) {
        return GeoObservationResponse.of(observationRepository.find(observationId)
                .orElseThrow(() -> ApiException.notFound("geo observation not found: " + observationId)));
    }

    /**
     * 查询单个冲突簇；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ClusterResponse getCluster(String clusterId) {
        GeoCluster cluster = clusterRepository.find(clusterId)
                .orElseThrow(() -> ApiException.notFound("cluster not found: " + clusterId));
        return ClusterResponse.of(cluster, observationRepository.findMemberIds(clusterId));
    }

    /**
     * 查询全部冲突簇（按簇标识排序）。
     */
    @Transactional(readOnly = true)
    public List<ClusterResponse> listClusters() {
        return clusterRepository.findAll().stream()
                .map(cluster -> ClusterResponse.of(cluster,
                        observationRepository.findMemberIds(cluster.clusterId())))
                .toList();
    }

    /**
     * 按标识查询基准重算记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public RecalcRecordResponse getRecalc(String recalcId) {
        return toResponse(recalcRepository.find(recalcId)
                .orElseThrow(() -> ApiException.notFound("recalc record not found: " + recalcId)));
    }

    /**
     * 按设备查询基准重算历史（按重算时刻先后排序）；设备未登记且无记录时返回空列表。
     */
    @Transactional(readOnly = true)
    public List<RecalcRecordResponse> listRecalcs(String deviceId) {
        return recalcRepository.findByDeviceId(deviceId).stream()
                .map(this::toResponse)
                .toList();
    }

    // ---------- 坐标换算与校验 ----------

    /**
     * 原始经纬度越界校验：纬度 [-90, 90]、经度 [-180, 180]，边界合法；越界返回 422。
     */
    private void validateRawCoordinates(double latitude, double longitude) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            throw ApiException.unprocessableEntity("latitude and longitude must be finite numbers");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw ApiException.unprocessableEntity("latitude out of range [-90, 90]: " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw ApiException.unprocessableEntity("longitude out of range [-180, 180]: " + longitude);
        }
    }

    /**
     * 按基准固定偏移换算统一坐标，并校验换算结果仍在合法范围内；任一步失败抛 422。
     */
    private double[] convertAndValidate(double rawLatitude, double rawLongitude, CoordinateFrame frame) {
        validateRawCoordinates(rawLatitude, rawLongitude);
        double[] unified = frame.toUnified(rawLatitude, rawLongitude);
        if (Double.isNaN(unified[0]) || Double.isNaN(unified[1])
                || unified[0] < -90.0 || unified[0] > 90.0
                || unified[1] < -180.0 || unified[1] > 180.0) {
            throw ApiException.unprocessableEntity(
                    "unified coordinate out of range after applying frame " + frame.frameVersion()
                            + ": lat=" + unified[0] + ", lon=" + unified[1]);
        }
        return unified;
    }

    /**
     * 解析采集时刻（ISO-8601，允许带 UTC 或时区偏移）；格式不合法返回 400。
     */
    private Instant parseCapturedAt(String capturedAt) {
        try {
            return OffsetDateTime.parse(capturedAt).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest(
                    "capturedAt must be an ISO-8601 date-time with offset (e.g. 2026-09-26T10:00:00Z)");
        }
    }

    // ---------- 自动簇全量重算 ----------

    /**
     * 提取当前全部未人工裁决簇的快照（按簇标识排序，成员按标识排序）。
     */
    private List<ClusterSnapshot> autoClusterSnapshots() {
        return clusterRepository.findAll().stream()
                .filter(cluster -> !cluster.manuallyResolved())
                .map(cluster -> new ClusterSnapshot(cluster.clusterId(),
                        observationRepository.findMemberIds(cluster.clusterId()),
                        cluster.winnerObservationId()))
                .sorted(Comparator.comparing(ClusterSnapshot::clusterId))
                .toList();
    }

    /**
     * 已人工裁决簇的全部成员观测标识：这些观测的统一坐标与簇归属冻结，不参与重算。
     */
    private Set<String> frozenObservationIds() {
        return clusterRepository.findAll().stream()
                .filter(GeoCluster::manuallyResolved)
                .flatMap(cluster -> observationRepository.findMemberIds(cluster.clusterId()).stream())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 全量重算所有未人工裁决簇：
     * 已人工裁决簇的成员冻结、不参与重算；其余观测按统一坐标球面距离与采集时刻差
     * （均含边界）两两判定，同簇关系经并查集传递；两人及以上的组成为新簇，
     * 自动胜出者为采集时刻最早（平局取标识最小）的观测。
     *
     * @return 重算后的全部自动簇快照（按簇标识排序）
     */
    private List<ClusterSnapshot> rebuildAutoClusters() {
        List<GeoCluster> existingClusters = clusterRepository.findAll();
        Set<String> frozenObservationIds = existingClusters.stream()
                .filter(GeoCluster::manuallyResolved)
                .flatMap(cluster -> observationRepository.findMemberIds(cluster.clusterId()).stream())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> frozenClusterIds = existingClusters.stream()
                .filter(GeoCluster::manuallyResolved)
                .map(GeoCluster::clusterId)
                .collect(java.util.stream.Collectors.toSet());

        List<GeoObservation> participants = observationRepository.findAll().stream()
                .filter(observation -> !frozenObservationIds.contains(observation.observationId())
                        && !frozenClusterIds.contains(observation.clusterId()))
                .sorted(Comparator.comparing(GeoObservation::capturedAtUtc)
                        .thenComparing(GeoObservation::observationId))
                .toList();

        // 拆除旧自动簇归属并删除旧自动簇行；人工裁决簇原样保留。
        for (GeoCluster cluster : existingClusters) {
            if (!cluster.manuallyResolved()) {
                for (String memberId : observationRepository.findMemberIds(cluster.clusterId())) {
                    observationRepository.updateCluster(memberId, null);
                }
                clusterRepository.delete(cluster.clusterId());
            }
        }

        // 并查集：两两同簇判定（距离与时刻差同时满足阈值，边界包含），关系可传递。
        int n = participants.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                GeoObservation a = participants.get(i);
                GeoObservation b = participants.get(j);
                if (GeoDistance.sameCluster(
                        a.unifiedLatitude(), a.unifiedLongitude(), a.capturedAtUtc(),
                        b.unifiedLatitude(), b.unifiedLongitude(), b.capturedAtUtc())) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<GeoObservation>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(root(parent, i), key -> new ArrayList<>()).add(participants.get(i));
        }

        List<ClusterSnapshot> snapshots = new ArrayList<>();
        // 已删除的旧自动簇标识可复用（相同成员集合得到相同标识，保证重算前后快照可比）；
        // 仅人工裁决簇与本次已分配标识需要避让。
        Set<String> reservedIds = new java.util.HashSet<>(frozenClusterIds);
        for (List<GeoObservation> group : groups.values()) {
            if (group.size() < 2) {
                // 单人不成簇：保持无簇归属。
                continue;
            }
            List<String> memberIds = group.stream()
                    .map(GeoObservation::observationId)
                    .sorted()
                    .toList();
            String winnerId = group.stream()
                    .min(Comparator.comparing(GeoObservation::capturedAtUtc)
                            .thenComparing(GeoObservation::observationId))
                    .orElseThrow(IllegalStateException::new)
                    .observationId();
            String clusterId = deterministicClusterId(memberIds, reservedIds);
            reservedIds.add(clusterId);
            clusterRepository.insert(new GeoCluster(clusterId, false, winnerId, null, null));
            for (String memberId : memberIds) {
                observationRepository.updateCluster(memberId, clusterId);
            }
            snapshots.add(new ClusterSnapshot(clusterId, memberIds, winnerId));
        }
        snapshots.sort(Comparator.comparing(ClusterSnapshot::clusterId));
        return snapshots;
    }

    private int root(int[] parent, int index) {
        int current = index;
        while (parent[current] != current) {
            parent[current] = parent[parent[current]];
            current = parent[current];
        }
        return current;
    }

    private void union(int[] parent, int left, int right) {
        int leftRoot = root(parent, left);
        int rightRoot = root(parent, right);
        if (leftRoot != rightRoot) {
            parent[leftRoot] = rightRoot;
        }
    }

    /**
     * 由成员标识集合生成确定性簇标识：相同成员集合在任意重算中得到相同标识，便于新旧簇比较；
     * 极小概率与已存在簇标识冲突时加盐重试。
     */
    private String deterministicClusterId(List<String> sortedMemberIds, Set<String> reservedIds) {
        String salt = "";
        while (true) {
            String digest = sha256("CLUSTER" + SEPARATOR + salt + SEPARATOR + String.join(",", sortedMemberIds));
            String candidate = "CL-" + digest.substring(0, 16);
            if (!reservedIds.contains(candidate)) {
                return candidate;
            }
            salt = salt + "S";
        }
    }

    // ---------- 幂等 ----------

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参返回 409；无记录返回 null 继续执行。
     */
    private <T> TypedOutcome<T> checkReplay(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new TypedOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    /**
     * 占位写入去重记录；并发同键主键冲突时读取已提交结果：同参重放、异参 409。
     * 业务失败时占位随事务回滚，不占键。
     */
    private <T> TypedOutcome<T> insertPlaceholder(String requestId, String fingerprint,
                                                  String operation, Class<T> bodyType) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new TypedOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
        }
    }

    /**
     * 业务成功后回填去重记录响应，与业务变更同事务提交。
     */
    private <T> TypedOutcome<T> complete(String requestId, HttpStatus status, T body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new TypedOutcome<>(status.value(), body);
    }

    // ---------- 工具 ----------

    private RecalcRecordResponse toResponse(FrameRecalcRecord record) {
        return new RecalcRecordResponse(
                record.recalcId(), record.deviceId(), record.oldFrameVersion(), record.newFrameVersion(),
                readSnapshots(record.oldClusters()), readSnapshots(record.newClusters()),
                record.recalcedAtUtc());
    }

    private List<ClusterSnapshot> readSnapshots(String json) {
        try {
            return objectMapper.readValue(json, SNAPSHOT_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize cluster snapshots", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize json", e);
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
     * 将 double 规范化为稳定十进制字符串用于指纹，保证 30.0 与 30.00 视为同参。
     */
    private String canonical(Double value) {
        if (value == null) {
            return "<null>";
        }
        return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
    }

    private String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        return sha256(raw.toString());
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 坐标写操作结果：HTTP 状态码与响应体。
     */
    public record TypedOutcome<T>(int status, T body) {
    }
}
