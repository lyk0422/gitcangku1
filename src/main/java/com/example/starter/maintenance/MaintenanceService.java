package com.example.starter.maintenance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 观测站维护窗口业务服务。
 *
 * <p>窗口登记、更正、观测提交、合并与人工裁决均在单事务内完成，按站点行锁串行化：
 * 窗口变更先提交则后续观测按新版本匹配；观测先提交则按提交时版本固化，后续删窗、补录不改写。
 *
 * <p>requestId 采用占位式幂等：同参重放首次结果，异参 409，业务失败回滚不占键。
 * 任一明细失败整批回滚，错误码可区分原因；数量或时长错误信息包含实际值、要求值与差额。
 */
@Service
public class MaintenanceService {

    private static final String SEPARATOR = "\u0001";

    private final StationRepository stationRepository;
    private final MaintenanceWindowRepository windowRepository;
    private final StationObservationRepository observationRepository;
    private final ClusterSnapshotRepository snapshotRepository;
    private final ClusterAdjudicationRepository adjudicationRepository;
    private final MaintenanceRequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MaintenanceService(StationRepository stationRepository,
                              MaintenanceWindowRepository windowRepository,
                              StationObservationRepository observationRepository,
                              ClusterSnapshotRepository snapshotRepository,
                              ClusterAdjudicationRepository adjudicationRepository,
                              MaintenanceRequestLogRepository requestLogRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.stationRepository = stationRepository;
        this.windowRepository = windowRepository;
        this.observationRepository = observationRepository;
        this.snapshotRepository = snapshotRepository;
        this.adjudicationRepository = adjudicationRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 站点登记 ----------

    /**
     * 登记观测站及其 UTC 启用时刻；stationId 已存在返回 409。
     */
    @Transactional
    public WriteOutcome<StationResponse> registerStation(RegisterStationRequest request) {
        String fingerprint = fingerprint("STATION", request.stationId(), request.enabledAtUtc().toString());
        OutcomeReplay<StationResponse> replayed = checkReplay(request.requestId(), fingerprint, StationResponse.class);
        if (replayed.replay() != null) {
            return replayed.replay();
        }
        WriteOutcome<StationResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "STATION", StationResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        if (stationRepository.findById(request.stationId()).isPresent()) {
            throw MaintenanceApiException.conflict("STATION_EXISTS",
                    "station already exists: " + request.stationId());
        }
        Station station;
        try {
            station = new Station(request.stationId(), request.enabledAtUtc(), Instant.now(clock));
            stationRepository.insert(station);
        } catch (DuplicateKeyException e) {
            // 并发登记同一 stationId：主键串行化，后到者 409
            throw MaintenanceApiException.conflict("STATION_EXISTS",
                    "station already exists: " + request.stationId());
        }
        return complete(request.requestId(), HttpStatus.CREATED, StationResponse.of(station));
    }

    // ---------- 窗口登记 / 更正 / 删除 ----------

    /**
     * 登记维护窗口：不得早于站点启用时刻，生效窗口不得与同站其他生效窗口重叠。
     */
    @Transactional
    public WriteOutcome<MaintenanceWindowResponse> registerWindow(String stationId, RegisterWindowRequest request) {
        String fingerprint = fingerprint("WINDOW", stationId, request.windowId(),
                request.startAtUtc().toString(), request.endAtUtc().toString());
        OutcomeReplay<MaintenanceWindowResponse> replayed =
                checkReplay(request.requestId(), fingerprint, MaintenanceWindowResponse.class);
        if (replayed.replay() != null) {
            return replayed.replay();
        }
        WriteOutcome<MaintenanceWindowResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "WINDOW", MaintenanceWindowResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        Station station = lockStation(stationId);
        if (windowRepository.findById(request.windowId()).isPresent()) {
            throw MaintenanceApiException.conflict("WINDOW_ID_EXISTS",
                    "window already exists: " + request.windowId());
        }
        MaintenanceWindow window = createWindow(station, request.windowId(),
                request.startAtUtc(), request.endAtUtc(), WindowType.REGISTER, null);
        return complete(request.requestId(), HttpStatus.CREATED, MaintenanceWindowResponse.of(window));
    }

    /**
     * 追加更正窗口：仅允许与其所更正的原窗口重叠；原窗口须属于同站且生效。
     */
    @Transactional
    public WriteOutcome<MaintenanceWindowResponse> correctWindow(CorrectWindowRequest request) {
        String fingerprint = fingerprint("CORRECT", request.windowId(), request.correctsWindowId(),
                request.startAtUtc().toString(), request.endAtUtc().toString());
        OutcomeReplay<MaintenanceWindowResponse> replayed =
                checkReplay(request.requestId(), fingerprint, MaintenanceWindowResponse.class);
        if (replayed.replay() != null) {
            return replayed.replay();
        }
        WriteOutcome<MaintenanceWindowResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "CORRECT", MaintenanceWindowResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        MaintenanceWindow target = windowRepository.findById(request.correctsWindowId())
                .orElseThrow(() -> MaintenanceApiException.notFound("WINDOW_NOT_FOUND",
                        "corrected window not found: " + request.correctsWindowId()));
        Station station = lockStation(target.stationId());
        MaintenanceWindow lockedTarget = windowRepository.findByIdForUpdate(request.correctsWindowId())
                .orElseThrow(() -> MaintenanceApiException.notFound("WINDOW_NOT_FOUND",
                        "corrected window not found: " + request.correctsWindowId()));
        if (lockedTarget.status() != WindowStatus.ACTIVE) {
            throw MaintenanceApiException.conflict("WINDOW_TARGET_NOT_ACTIVE",
                    "corrected window is not active: " + request.correctsWindowId());
        }
        if (windowRepository.findById(request.windowId()).isPresent()) {
            throw MaintenanceApiException.conflict("WINDOW_ID_EXISTS",
                    "window already exists: " + request.windowId());
        }
        validateRange(request.startAtUtc(), request.endAtUtc());
        MaintenanceWindow window = createWindowLocked(station, request.windowId(),
                request.startAtUtc(), request.endAtUtc(), WindowType.CORRECTION,
                request.correctsWindowId());
        return complete(request.requestId(), HttpStatus.CREATED, MaintenanceWindowResponse.of(window));
    }

    /**
     * 删除维护窗口：已封存（被合并快照固化）窗口拒绝删除，只能追加更正窗口。
     */
    @Transactional
    public WriteOutcome<MaintenanceWindowResponse> deleteWindow(String windowId, DeleteWindowRequest request) {
        String fingerprint = fingerprint("WINDOW_DELETE", windowId);
        OutcomeReplay<MaintenanceWindowResponse> replayed =
                checkReplay(request.requestId(), fingerprint, MaintenanceWindowResponse.class);
        if (replayed.replay() != null) {
            return replayed.replay();
        }
        WriteOutcome<MaintenanceWindowResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "WINDOW_DELETE", MaintenanceWindowResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        MaintenanceWindow existing = windowRepository.findById(windowId)
                .orElseThrow(() -> MaintenanceApiException.notFound("WINDOW_NOT_FOUND",
                        "window not found: " + windowId));
        lockStation(existing.stationId());
        MaintenanceWindow window = windowRepository.findByIdForUpdate(windowId)
                .orElseThrow(() -> MaintenanceApiException.notFound("WINDOW_NOT_FOUND",
                        "window not found: " + windowId));
        if (window.status() == WindowStatus.DELETED) {
            throw MaintenanceApiException.conflict("WINDOW_NOT_ACTIVE",
                    "window already deleted: " + windowId);
        }
        if (windowRepository.isSealedBySnapshot(windowId)) {
            throw MaintenanceApiException.conflict("WINDOW_SEALED",
                    "window is sealed by merge snapshots and cannot be modified or deleted; "
                            + "append a correction window instead: " + windowId);
        }
        windowRepository.markDeleted(windowId);
        MaintenanceWindow deleted = new MaintenanceWindow(window.windowId(), window.stationId(),
                window.maintenanceVersion(), window.startAtUtc(), window.endAtUtc(),
                window.windowType(), window.correctsWindowId(), WindowStatus.DELETED, window.createdAtUtc());
        return complete(request.requestId(), HttpStatus.OK, MaintenanceWindowResponse.of(deleted));
    }

    // ---------- 观测批量提交 ----------

    /**
     * 批量提交观测：逐条按提交时刻匹配当时生效窗口并固化维护标记；
     * 混合多站、批内重复、站点引用缺失等任一失败整批回滚。
     */
    @Transactional
    public WriteOutcome<SubmitObservationsResponse> submitObservations(SubmitObservationsRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(String.valueOf(request.observations().size()));
        for (ObservationItem item : request.observations()) {
            parts.add(item.observationId());
            parts.add(item.stationId());
            parts.add(item.clusterId() == null ? "<null>" : item.clusterId());
            parts.add(item.submittedAtUtc().toString());
            parts.add(item.payload());
        }
        String fingerprint = fingerprint("SUBMIT", parts.toArray(String[]::new));
        OutcomeReplay<SubmitObservationsResponse> replayed =
                checkReplay(request.requestId(), fingerprint, SubmitObservationsResponse.class);
        if (replayed.replay() != null) {
            return replayed.replay();
        }
        WriteOutcome<SubmitObservationsResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "SUBMIT", SubmitObservationsResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        // 先核对整批最终状态：批内重复标识、已存在标识、站点引用合法性与同簇同站，全部通过才落库。
        Set<String> batchIds = new LinkedHashSet<>();
        Set<String> batchStations = new LinkedHashSet<>();
        Map<String, String> clusterStations = new LinkedHashMap<>();
        for (int i = 0; i < request.observations().size(); i++) {
            ObservationItem item = request.observations().get(i);
            if (!batchIds.add(item.observationId())) {
                throw MaintenanceApiException.conflict("OBSERVATION_DUPLICATE",
                        "duplicate observationId within batch at index " + i + ": " + item.observationId());
            }
            if (observationRepository.findById(item.observationId()).isPresent()) {
                throw MaintenanceApiException.conflict("OBSERVATION_EXISTS",
                        "observation already exists: " + item.observationId());
            }
            batchStations.add(item.stationId());
            if (item.clusterId() != null) {
                String previous = clusterStations.putIfAbsent(item.clusterId(), item.stationId());
                if (previous != null && !previous.equals(item.stationId())) {
                    throw MaintenanceApiException.unprocessable("CLUSTER_MIXED_STATIONS",
                            "cluster " + item.clusterId() + " mixes stations within batch: actual "
                                    + previous + " and " + item.stationId());
                }
            }
        }
        // 跨批次校验：同簇已落库观测若属于其他站点，本批同样拒绝，保证冲突簇始终同站。
        for (Map.Entry<String, String> entry : clusterStations.entrySet()) {
            List<String> persistedStations = observationRepository.findStationIdsByCluster(entry.getKey());
            for (String persistedStation : persistedStations) {
                if (!persistedStation.equals(entry.getValue())) {
                    throw MaintenanceApiException.unprocessable("CLUSTER_MIXED_STATIONS",
                            "cluster " + entry.getKey() + " already contains observations of station "
                                    + persistedStation + ", cannot add station " + entry.getValue());
                }
            }
        }

        // 锁定本批涉及的全部站点行：按标识固定顺序加锁，避免多站批次交叉等待死锁；
        // 与窗口登记/更正/删除串行化，保证逐条匹配按提交顺序看到确定的窗口版本集。
        List<Station> lockedStations = new ArrayList<>();
        for (String stationId : batchStations.stream().sorted().toList()) {
            lockedStations.add(lockStation(stationId));
        }

        Instant now = Instant.now(clock);
        List<ObservationResultResponse> results = new ArrayList<>();
        int affectedCount = 0;
        for (ObservationItem item : request.observations()) {
            List<MaintenanceWindow> hits = windowRepository.findActiveAtTime(item.stationId(), item.submittedAtUtc());
            MaintenanceWindow matched = hits.isEmpty() ? null
                    : hits.stream().max(Comparator.comparingInt(MaintenanceWindow::maintenanceVersion)).orElseThrow();
            boolean affected = matched != null;
            if (affected) {
                affectedCount++;
            }
            StationObservation observation = new StationObservation(
                    item.observationId(), item.stationId(), item.clusterId(), item.submittedAtUtc(),
                    item.payload(), affected,
                    matched == null ? null : matched.windowId(),
                    matched == null ? null : matched.maintenanceVersion(),
                    now);
            try {
                observationRepository.insert(observation);
            } catch (DuplicateKeyException e) {
                // 并发提交同一 observationId：主键串行化，后到者失败并整批回滚
                throw MaintenanceApiException.conflict("OBSERVATION_EXISTS",
                        "observation already exists: " + item.observationId());
            }
            results.add(ObservationResultResponse.of(observation));
        }
        SubmitObservationsResponse body = new SubmitObservationsResponse(
                request.requestId(), results.size(), affectedCount, results);
        return complete(request.requestId(), HttpStatus.CREATED, body);
    }

    // ---------- 冲突簇合并与裁决 ----------

    /**
     * 合并冲突簇：受维护影响观测不可自动胜出；存在未受影响观测时取提交时刻最晚者（平局按标识），
     * 全部受影响时结果为 PENDING_MANUAL。快照固化证据维护标记与窗口版本，随后删窗/补录不改写。
     */
    @Transactional
    public WriteOutcome<SnapshotResponse> mergeCluster(String clusterId, MergeClusterRequest request) {
        String fingerprint = fingerprint("CLUSTER_MERGE", clusterId);
        OutcomeReplay<SnapshotResponse> replayed =
                checkReplay(request.requestId(), fingerprint, SnapshotResponse.class);
        if (replayed.replay() != null) {
            return replayed.replay();
        }
        WriteOutcome<SnapshotResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "CLUSTER_MERGE", SnapshotResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        List<StationObservation> observations = observationRepository.findByCluster(clusterId);
        if (observations.isEmpty()) {
            throw MaintenanceApiException.notFound("CLUSTER_NOT_FOUND",
                    "cluster has no observations: " + clusterId);
        }
        String stationId = observations.get(0).stationId();
        // 站点行锁串行化同簇并发合并，保证快照序号与胜出结果确定。
        lockStation(stationId);
        List<StationObservation> locked = observationRepository.findByClusterForUpdate(clusterId);

        List<SnapshotEvidence> evidence = locked.stream()
                .map(o -> new SnapshotEvidence(o.observationId(), o.submittedAtUtc(), o.payload(),
                        o.maintenanceAffected(), o.matchedWindowId(), o.matchedMaintenanceVersion()))
                .toList();
        List<SnapshotWindow> windows = frozenWindows(locked);
        Optional<SnapshotEvidence> winner = MaintenanceRules.pickWinner(evidence);

        int sequence = snapshotRepository.findLatestHeader(clusterId).map(ClusterSnapshotRecord::sequence).orElse(0) + 1;
        Instant now = Instant.now(clock);
        SnapshotStatus status = winner.isPresent() ? SnapshotStatus.MERGED : SnapshotStatus.PENDING_MANUAL;
        long snapshotId;
        try {
            snapshotId = snapshotRepository.insertHeader(clusterId, stationId, sequence, status,
                    winner.map(SnapshotEvidence::observationId).orElse(null), now);
        } catch (DuplicateKeyException e) {
            // 极端并发下序号撞键：按冲突处理（事务回滚，不占键），由调用方重试。
            throw MaintenanceApiException.conflict("CLUSTER_SNAPSHOT_SEQUENCE_CONFLICT",
                    "concurrent merge on cluster: " + clusterId);
        }
        for (int i = 0; i < evidence.size(); i++) {
            snapshotRepository.insertEvidence(snapshotId, i, evidence.get(i));
        }
        for (int i = 0; i < windows.size(); i++) {
            snapshotRepository.insertWindow(snapshotId, i, windows.get(i));
        }
        ClusterSnapshotRecord record = new ClusterSnapshotRecord(
                snapshotId, clusterId, stationId, sequence, status,
                winner.map(SnapshotEvidence::observationId).orElse(null), now, evidence, windows);
        return complete(request.requestId(), HttpStatus.OK, SnapshotResponse.of(record));
    }

    /**
     * 人工裁决：允许选择受维护影响观测，但理由与裁决人必填；同一待裁决快照只可裁决一次。
     */
    @Transactional
    public WriteOutcome<AdjudicationResponse> adjudicateCluster(String clusterId, AdjudicateClusterRequest request) {
        String fingerprint = fingerprint("ADJUDICATE", clusterId, request.adjudicationId(),
                request.observationId(), request.reason(), request.adjudicator());
        OutcomeReplay<AdjudicationResponse> replayed =
                checkReplay(request.requestId(), fingerprint, AdjudicationResponse.class);
        if (replayed.replay() != null) {
            return replayed.replay();
        }
        WriteOutcome<AdjudicationResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "ADJUDICATE", AdjudicationResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        ClusterSnapshotRecord latest = snapshotRepository.findLatestHeader(clusterId)
                .orElseThrow(() -> MaintenanceApiException.notFound("SNAPSHOT_NOT_FOUND",
                        "no merge snapshot for cluster: " + clusterId));
        if (latest.status() != SnapshotStatus.PENDING_MANUAL) {
            throw MaintenanceApiException.conflict("SNAPSHOT_NOT_PENDING",
                    "latest snapshot " + latest.snapshotId() + " is already " + latest.status().name()
                            + ", manual adjudication is only allowed for PENDING_MANUAL snapshots");
        }
        // 站点行锁串行化同簇并发裁决；快照是否已裁决最终由 cluster_adjudication(snapshot_id) 唯一键保证。
        lockStation(latest.stationId());
        ClusterSnapshotRecord lockedLatest = snapshotRepository.findLatestHeader(clusterId)
                .orElseThrow(() -> MaintenanceApiException.notFound("SNAPSHOT_NOT_FOUND",
                        "no merge snapshot for cluster: " + clusterId));
        if (lockedLatest.status() != SnapshotStatus.PENDING_MANUAL) {
            throw MaintenanceApiException.conflict("SNAPSHOT_NOT_PENDING",
                    "latest snapshot " + lockedLatest.snapshotId() + " is already "
                            + lockedLatest.status().name());
        }

        // adjudicationId 全局复用：同参重放，异参或跨簇复用 409。
        ClusterAdjudication existing = adjudicationRepository.findByAdjudicationId(request.adjudicationId())
                .orElse(null);
        if (existing != null) {
            if (!sameAdjudicationParams(existing, clusterId, lockedLatest.snapshotId(), request)) {
                throw MaintenanceApiException.conflict("ADJUDICATION_ID_REUSED",
                        "adjudicationId reused with different parameters: " + request.adjudicationId());
            }
            return complete(request.requestId(), HttpStatus.OK, AdjudicationResponse.of(existing));
        }

        List<SnapshotEvidence> evidence = snapshotRepository.findEvidence(lockedLatest.snapshotId());
        SnapshotEvidence chosen = evidence.stream()
                .filter(e -> e.observationId().equals(request.observationId()))
                .findFirst()
                .orElseThrow(() -> MaintenanceApiException.notFound("OBSERVATION_NOT_IN_CLUSTER",
                        "observation " + request.observationId() + " is not part of the pending evidence "
                                + "of snapshot " + lockedLatest.snapshotId()));

        ClusterAdjudication adjudication = new ClusterAdjudication(
                request.adjudicationId(), lockedLatest.snapshotId(), clusterId, lockedLatest.stationId(),
                chosen.observationId(), chosen.maintenanceAffected(), request.reason(), request.adjudicator(),
                request.requestId(), Instant.now(clock));
        try {
            adjudicationRepository.insert(adjudication);
        } catch (DuplicateKeyException e) {
            // 并发下 adjudicationId 或同快照裁决唯一键冲突：重读后同参重放，否则 409。
            ClusterAdjudication winner = adjudicationRepository.findByAdjudicationId(request.adjudicationId())
                    .or(() -> adjudicationRepository.findBySnapshotId(lockedLatest.snapshotId()))
                    .orElseThrow(() -> MaintenanceApiException.conflict("ADJUDICATION_CONFLICT",
                            "snapshot already adjudicated: " + lockedLatest.snapshotId()));
            if (!sameAdjudicationParams(winner, clusterId, lockedLatest.snapshotId(), request)) {
                throw MaintenanceApiException.conflict("ADJUDICATION_CONFLICT",
                        "snapshot already adjudicated with different parameters: " + lockedLatest.snapshotId());
            }
            return complete(request.requestId(), HttpStatus.OK, AdjudicationResponse.of(winner));
        }
        return complete(request.requestId(), HttpStatus.OK, AdjudicationResponse.of(adjudication));
    }

    // ---------- 只读查询 ----------

    /**
     * 查询观测站。
     */
    @Transactional(readOnly = true)
    public StationResponse getStation(String stationId) {
        return StationResponse.of(stationRepository.findById(stationId)
                .orElseThrow(() -> MaintenanceApiException.notFound("STATION_NOT_FOUND",
                        "station not found: " + stationId)));
    }

    /**
     * 查询单个维护窗口。
     */
    @Transactional(readOnly = true)
    public MaintenanceWindowResponse getWindow(String windowId) {
        return MaintenanceWindowResponse.of(windowRepository.findById(windowId)
                .orElseThrow(() -> MaintenanceApiException.notFound("WINDOW_NOT_FOUND",
                        "window not found: " + windowId)));
    }

    /**
     * 查询站点生效窗口明细（只读诊断），按开始时刻升序。
     */
    @Transactional(readOnly = true)
    public List<MaintenanceWindowResponse> listActiveWindows(String stationId) {
        stationRepository.findById(stationId)
                .orElseThrow(() -> MaintenanceApiException.notFound("STATION_NOT_FOUND",
                        "station not found: " + stationId));
        return windowRepository.findActiveByStation(stationId).stream()
                .map(MaintenanceWindowResponse::of).toList();
    }

    /**
     * 查询单条原始观测及其固化维护标记。
     */
    @Transactional(readOnly = true)
    public ObservationResultResponse getObservation(String observationId) {
        return ObservationResultResponse.of(observationRepository.findById(observationId)
                .orElseThrow(() -> MaintenanceApiException.notFound("OBSERVATION_NOT_FOUND",
                        "observation not found: " + observationId)));
    }

    /**
     * 查询冲突簇当前全部观测（只读诊断），按提交时刻升序、平局按标识升序。
     */
    @Transactional(readOnly = true)
    public List<ObservationResultResponse> listClusterObservations(String clusterId) {
        List<StationObservation> observations = observationRepository.findByCluster(clusterId);
        if (observations.isEmpty()) {
            throw MaintenanceApiException.notFound("CLUSTER_NOT_FOUND",
                    "cluster has no observations: " + clusterId);
        }
        return observations.stream().map(ObservationResultResponse::of).toList();
    }

    /**
     * 按主键查询合并快照（含固化证据维护标记与窗口版本）。
     */
    @Transactional(readOnly = true)
    public SnapshotResponse getSnapshot(long snapshotId) {
        ClusterSnapshotRecord header = snapshotRepository.findHeaderById(snapshotId)
                .orElseThrow(() -> MaintenanceApiException.notFound("SNAPSHOT_NOT_FOUND",
                        "snapshot not found: " + snapshotId));
        return SnapshotResponse.of(assemble(header));
    }

    /**
     * 查询冲突簇全部合并快照历史（只读），按序号升序。
     */
    @Transactional(readOnly = true)
    public List<SnapshotResponse> listSnapshots(String clusterId) {
        List<ClusterSnapshotRecord> headers = snapshotRepository.findHeadersByCluster(clusterId);
        if (headers.isEmpty()) {
            throw MaintenanceApiException.notFound("SNAPSHOT_NOT_FOUND",
                    "no merge snapshot for cluster: " + clusterId);
        }
        return headers.stream().map(this::assemble).map(SnapshotResponse::of).toList();
    }

    /**
     * 按全局唯一裁决标识查询裁决记录。
     */
    @Transactional(readOnly = true)
    public AdjudicationResponse getAdjudication(String adjudicationId) {
        return AdjudicationResponse.of(adjudicationRepository.findByAdjudicationId(adjudicationId)
                .orElseThrow(() -> MaintenanceApiException.notFound("ADJUDICATION_NOT_FOUND",
                        "adjudication not found: " + adjudicationId)));
    }

    /**
     * 按冲突簇查询裁决历史（只读），按裁决时刻升序。
     */
    @Transactional(readOnly = true)
    public List<AdjudicationResponse> listAdjudications(String clusterId) {
        return adjudicationRepository.findByClusterId(clusterId).stream()
                .map(AdjudicationResponse::of).toList();
    }

    // ---------- 内部辅助 ----------

    /**
     * 锁定站点行并返回站点；不存在 404。所有窗口变更、提交与合并均经此串行化。
     */
    private Station lockStation(String stationId) {
        return stationRepository.findByIdForUpdate(stationId)
                .orElseThrow(() -> MaintenanceApiException.notFound("STATION_NOT_FOUND",
                        "station not found: " + stationId));
    }

    /**
     * 校验区间并在站点锁内构造、落库 REGISTER 窗口。
     */
    private MaintenanceWindow createWindow(Station station, String windowId,
                                           Instant startAtUtc, Instant endAtUtc,
                                           WindowType type, String correctsWindowId) {
        validateRange(startAtUtc, endAtUtc);
        return createWindowLocked(station, windowId, startAtUtc, endAtUtc, type, correctsWindowId);
    }

    /**
     * 调用方已完成区间校验与站点锁定：校验启用时刻、重叠规则并分配版本号后落库。
     */
    private MaintenanceWindow createWindowLocked(Station station, String windowId,
                                                 Instant startAtUtc, Instant endAtUtc,
                                                 WindowType type, String correctsWindowId) {
        if (startAtUtc.isBefore(station.enabledAtUtc())) {
            long diffSeconds = Duration.between(startAtUtc, station.enabledAtUtc()).getSeconds();
            throw MaintenanceApiException.conflict("WINDOW_BEFORE_ENABLED",
                    "window start " + startAtUtc + " must not be earlier than station enabled time "
                            + station.enabledAtUtc() + "; actual=" + startAtUtc
                            + ", required>=" + station.enabledAtUtc() + ", difference=" + diffSeconds + "s");
        }
        List<MaintenanceWindow> existing = windowRepository.findByStationForUpdate(station.stationId());
        for (MaintenanceWindow other : existing) {
            if (other.status() != WindowStatus.ACTIVE) {
                continue;
            }
            if (!MaintenanceRules.overlaps(startAtUtc, endAtUtc, other.startAtUtc(), other.endAtUtc())) {
                continue;
            }
            // 更正窗口仅允许与其所更正的原窗口重叠；其余重叠（含与其他更正窗口）一律拒绝。
            boolean allowedCorrectionOverlap =
                    type == WindowType.CORRECTION && other.windowId().equals(correctsWindowId);
            if (!allowedCorrectionOverlap) {
                long overlapSeconds = overlapSeconds(startAtUtc, endAtUtc, other.startAtUtc(), other.endAtUtc());
                throw MaintenanceApiException.conflict("WINDOW_OVERLAP",
                        "window overlaps active window " + other.windowId() + " of station "
                                + station.stationId() + "; overlapping duration=" + overlapSeconds
                                + "s, required overlap=0s");
            }
        }
        int nextVersion = existing.stream().mapToInt(MaintenanceWindow::maintenanceVersion).max().orElse(0) + 1;
        MaintenanceWindow window = new MaintenanceWindow(windowId, station.stationId(), nextVersion,
                startAtUtc, endAtUtc, type, correctsWindowId, WindowStatus.ACTIVE, Instant.now(clock));
        try {
            windowRepository.insert(window);
        } catch (DuplicateKeyException e) {
            throw MaintenanceApiException.conflict("WINDOW_ID_EXISTS", "window already exists: " + windowId);
        }
        return window;
    }

    /**
     * 校验左闭右开区间时长必须为正；失败信息给出实际时长（秒）与要求值（&gt;0s）及差额。
     */
    private void validateRange(Instant startAtUtc, Instant endAtUtc) {
        if (!endAtUtc.isAfter(startAtUtc)) {
            long actualSeconds = Duration.between(startAtUtc, endAtUtc).getSeconds();
            throw MaintenanceApiException.badRequest("INVALID_WINDOW_RANGE",
                    "end must be after start for half-open interval [start, end); actual duration="
                            + actualSeconds + "s, required>0s, difference=" + (1L - actualSeconds) + "s");
        }
    }

    /**
     * 计算两个区间的重叠秒数，仅在确认重叠后调用。
     */
    private long overlapSeconds(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd) {
        Instant start = aStart.isAfter(bStart) ? aStart : bStart;
        Instant end = aEnd.isBefore(bEnd) ? aEnd : bEnd;
        return Duration.between(start, end).getSeconds();
    }

    /**
     * 由簇内观测固化的命中窗口构造快照窗口列表：按窗口去重、按维护版本升序。
     */
    private List<SnapshotWindow> frozenWindows(List<StationObservation> observations) {
        Map<String, Integer> versions = new LinkedHashMap<>();
        for (StationObservation observation : observations) {
            if (observation.matchedWindowId() != null) {
                versions.putIfAbsent(observation.matchedWindowId(), observation.matchedMaintenanceVersion());
            }
        }
        return versions.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(entry -> new SnapshotWindow(entry.getKey(), entry.getValue()))
                .toList();
    }

    private ClusterSnapshotRecord assemble(ClusterSnapshotRecord header) {
        return new ClusterSnapshotRecord(header.snapshotId(), header.clusterId(), header.stationId(),
                header.sequence(), header.status(), header.winnerObservationId(), header.createdAtUtc(),
                snapshotRepository.findEvidence(header.snapshotId()),
                snapshotRepository.findWindows(header.snapshotId()));
    }

    /**
     * 判断裁决请求参数与已存在记录是否一致（用于 adjudicationId 同参重放）。
     */
    private boolean sameAdjudicationParams(ClusterAdjudication record, String clusterId,
                                           long snapshotId, AdjudicateClusterRequest request) {
        return record.clusterId().equals(clusterId)
                && record.snapshotId() == snapshotId
                && record.observationId().equals(request.observationId())
                && record.reason().equals(request.reason())
                && record.adjudicator().equals(request.adjudicator());
    }

    /**
     * 幂等检查：同键同参返回原成功结果；同键异参 409；无记录继续执行。
     */
    private <T> OutcomeReplay<T> checkReplay(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<MaintenanceRequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return new OutcomeReplay<>(null);
        }
        MaintenanceRequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw MaintenanceApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId reused with different parameters: " + requestId);
        }
        try {
            return new OutcomeReplay<>(new WriteOutcome<>(entry.responseStatus(),
                    objectMapper.readValue(entry.responseBody(), bodyType)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    /**
     * 占位写入去重记录；并发同键时读已提交结果：同参重放、异参 409；正常占位返回 null。
     */
    private <T> WriteOutcome<T> insertPlaceholder(String requestId, String fingerprint,
                                                  String operation, Class<T> bodyType) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            MaintenanceRequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> MaintenanceApiException.conflict("REQUEST_ID_CONFLICT",
                            "requestId conflict: " + requestId));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw MaintenanceApiException.conflict("REQUEST_ID_CONFLICT",
                        "requestId reused with different parameters: " + requestId);
            }
            try {
                return new WriteOutcome<>(entry.responseStatus(),
                        objectMapper.readValue(entry.responseBody(), bodyType));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("failed to deserialize stored response", ex);
            }
        }
    }

    /**
     * 业务成功后回填去重记录响应并构造结果，与业务变更同事务提交。
     */
    private <T> WriteOutcome<T> complete(String requestId, HttpStatus status, T body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
        return new WriteOutcome<>(status.value(), body);
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

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record WriteOutcome<T>(int status, T body) {
    }

    /**
     * 幂等重放内部载体：replay 为 null 表示无重放、继续正常流程。
     */
    private record OutcomeReplay<T>(WriteOutcome<T> replay) {
    }
}
