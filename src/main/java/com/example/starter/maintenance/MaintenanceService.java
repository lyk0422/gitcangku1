package com.example.starter.maintenance;

import com.example.starter.observation.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 观测站维护窗口与离线合并证据标记业务服务。
 *
 * <p>时间语义：所有业务时间为 UTC；维护窗口为左闭右开区间 [startUtc, endUtc)。
 *
 * <p>写操作（站点登记/窗口登记/窗口更正/窗口撤回/批量提交/合并/人工裁决）均在单事务内完成：
 * 先写入幂等占位，再校验完整最终状态，最后一次性提交；任何失败整体回滚，不占幂等键。
 * 窗口变更与批量提交通过站点行锁（SELECT ... FOR UPDATE）串行化，按提交顺序裁决：
 * 窗口变更先提交则后续观测按新窗口标记，观测先提交则按提交时窗口版本保留。
 *
 * <p>合并快照在合并时刻固化各提交的维护标记与窗口版本，不可变；后续删窗、补录或
 * 人工裁决均不改写历史快照。被快照覆盖（封存）的窗口不可修改/撤回，只能追加更正窗口。
 */
@Service
public class MaintenanceService {

    private static final TypeReference<List<MergeMarkEntry>> MARK_LIST_TYPE = new TypeReference<>() {
    };

    private final StationRepository stationRepository;
    private final StationObservationRepository observationRepository;
    private final IdempotencySupport idempotencySupport;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MaintenanceService(StationRepository stationRepository,
                              StationObservationRepository observationRepository,
                              IdempotencySupport idempotencySupport,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.stationRepository = stationRepository;
        this.observationRepository = observationRepository;
        this.idempotencySupport = idempotencySupport;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 观测站 ----------

    /**
     * 登记观测站；站点已存在返回 409。
     */
    @Transactional
    public Outcome<StationResponse> registerStation(RegisterStationRequest request) {
        String fingerprint = idempotencySupport.fingerprint("STATION_REGISTER", request.stationId(),
                request.activatedAtUtc().toString());
        IdempotencySupport.Replay<StationResponse> replayed =
                idempotencySupport.checkReplay(request.requestId(), fingerprint, StationResponse.class);
        if (replayed != null) {
            return new Outcome<>(replayed.status(), replayed.body());
        }
        IdempotencySupport.Replay<StationResponse> concurrent = idempotencySupport.insertPlaceholder(
                request.requestId(), fingerprint, "STATION_REGISTER", StationResponse.class);
        if (concurrent != null) {
            return new Outcome<>(concurrent.status(), concurrent.body());
        }

        if (stationRepository.findStation(request.stationId()).isPresent()) {
            throw ApiException.conflict("station already exists: " + request.stationId(), null);
        }
        StationRecord station = new StationRecord(request.stationId(), request.activatedAtUtc());
        try {
            stationRepository.insertStation(station);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("station already exists: " + request.stationId(), null);
        }
        StationResponse body = StationResponse.of(station);
        idempotencySupport.complete(request.requestId(), HttpStatus.CREATED.value(), body);
        return new Outcome<>(HttpStatus.CREATED.value(), body);
    }

    /**
     * 查询观测站明细；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public StationResponse getStation(String stationId) {
        return StationResponse.of(stationRepository.findStation(stationId)
                .orElseThrow(() -> ApiException.notFound("station not found: " + stationId)));
    }

    // ---------- 维护窗口 ----------

    /**
     * 登记维护窗口：校验区间合法、不早于站点启用时刻、不与 ACTIVE 窗口重叠。
     */
    @Transactional
    public Outcome<WindowResponse> registerWindow(String stationId, RegisterWindowRequest request) {
        String fingerprint = idempotencySupport.fingerprint("WINDOW_REGISTER", stationId, request.windowId(),
                request.startUtc().toString(), request.endUtc().toString(), String.valueOf(request.windowVersion()));
        IdempotencySupport.Replay<WindowResponse> replayed =
                idempotencySupport.checkReplay(request.requestId(), fingerprint, WindowResponse.class);
        if (replayed != null) {
            return new Outcome<>(replayed.status(), replayed.body());
        }
        IdempotencySupport.Replay<WindowResponse> concurrent = idempotencySupport.insertPlaceholder(
                request.requestId(), fingerprint, "WINDOW_REGISTER", WindowResponse.class);
        if (concurrent != null) {
            return new Outcome<>(concurrent.status(), concurrent.body());
        }

        StationRecord station = stationRepository.findStationForUpdate(stationId)
                .orElseThrow(() -> ApiException.notFound("station not found: " + stationId));
        validateInterval(request.startUtc(), request.endUtc());
        validateNotBeforeActivation(station, request.startUtc());
        if (stationRepository.findWindow(request.windowId()).isPresent()) {
            throw ApiException.conflict("window already exists: " + request.windowId(), null);
        }
        List<MaintenanceWindowRecord> overlapping =
                stationRepository.findOverlappingActiveWindows(stationId, request.startUtc(), request.endUtc());
        if (!overlapping.isEmpty()) {
            MaintenanceWindowRecord first = overlapping.get(0);
            throw ApiException.conflict("maintenance window overlaps active window: " + first.windowId(), null,
                    Map.of("overlappingWindowId", first.windowId(),
                            "overlappingStartUtc", first.startUtc().toString(),
                            "overlappingEndUtc", first.endUtc().toString(),
                            "requestedStartUtc", request.startUtc().toString(),
                            "requestedEndUtc", request.endUtc().toString()));
        }
        MaintenanceWindowRecord window = new MaintenanceWindowRecord(request.windowId(), stationId,
                request.startUtc(), request.endUtc(), request.windowVersion(),
                MaintenanceWindowRecord.STATUS_ACTIVE, null, request.requestId(), Instant.now(clock));
        try {
            stationRepository.insertWindow(window);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("window already exists: " + request.windowId(), null);
        }
        WindowResponse body = WindowResponse.of(window, false);
        idempotencySupport.complete(request.requestId(), HttpStatus.CREATED.value(), body);
        return new Outcome<>(HttpStatus.CREATED.value(), body);
    }

    /**
     * 追加更正窗口：原窗口置为 SUPERSEDED，更正窗口以 ACTIVE 落库；更正版本必须大于原版本。
     * 更正窗口不得与其他 ACTIVE 窗口重叠（被更正窗口除外）。
     */
    @Transactional
    public Outcome<WindowResponse> correctWindow(String windowId, CorrectWindowRequest request) {
        String fingerprint = idempotencySupport.fingerprint("WINDOW_CORRECT", windowId,
                request.correctionWindowId(), request.startUtc().toString(), request.endUtc().toString(),
                String.valueOf(request.windowVersion()));
        IdempotencySupport.Replay<WindowResponse> replayed =
                idempotencySupport.checkReplay(request.requestId(), fingerprint, WindowResponse.class);
        if (replayed != null) {
            return new Outcome<>(replayed.status(), replayed.body());
        }
        IdempotencySupport.Replay<WindowResponse> concurrent = idempotencySupport.insertPlaceholder(
                request.requestId(), fingerprint, "WINDOW_CORRECT", WindowResponse.class);
        if (concurrent != null) {
            return new Outcome<>(concurrent.status(), concurrent.body());
        }

        MaintenanceWindowRecord target = stationRepository.findWindow(windowId)
                .orElseThrow(() -> ApiException.notFound("window not found: " + windowId));
        StationRecord station = stationRepository.findStationForUpdate(target.stationId())
                .orElseThrow(() -> ApiException.notFound("station not found: " + target.stationId()));
        validateInterval(request.startUtc(), request.endUtc());
        validateNotBeforeActivation(station, request.startUtc());
        if (!MaintenanceWindowRecord.STATUS_ACTIVE.equals(target.status())) {
            throw ApiException.conflict(
                    "only ACTIVE window can be corrected, current status: " + target.status(), null,
                    Map.of("windowId", windowId, "currentStatus", target.status(),
                            "requiredStatus", MaintenanceWindowRecord.STATUS_ACTIVE));
        }
        if (request.windowVersion() <= target.windowVersion()) {
            throw ApiException.badRequest(
                    "correction windowVersion must be greater than corrected window version", Map.of(
                            "requiredMinWindowVersion", target.windowVersion() + 1,
                            "actualWindowVersion", request.windowVersion()));
        }
        if (stationRepository.findWindow(request.correctionWindowId()).isPresent()) {
            throw ApiException.conflict("window already exists: " + request.correctionWindowId(), null);
        }
        List<MaintenanceWindowRecord> overlapping = stationRepository.findOverlappingActiveWindows(
                target.stationId(), request.startUtc(), request.endUtc()).stream()
                .filter(w -> !w.windowId().equals(windowId))
                .toList();
        if (!overlapping.isEmpty()) {
            MaintenanceWindowRecord first = overlapping.get(0);
            throw ApiException.conflict("correction window overlaps active window: " + first.windowId(), null,
                    Map.of("overlappingWindowId", first.windowId(),
                            "overlappingStartUtc", first.startUtc().toString(),
                            "overlappingEndUtc", first.endUtc().toString()));
        }
        MaintenanceWindowRecord correction = new MaintenanceWindowRecord(request.correctionWindowId(),
                target.stationId(), request.startUtc(), request.endUtc(), request.windowVersion(),
                MaintenanceWindowRecord.STATUS_ACTIVE, windowId, request.requestId(), Instant.now(clock));
        try {
            stationRepository.insertWindow(correction);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("window already exists: " + request.correctionWindowId(), null);
        }
        stationRepository.updateWindowStatus(windowId, MaintenanceWindowRecord.STATUS_SUPERSEDED);
        WindowResponse body = WindowResponse.of(correction, false);
        idempotencySupport.complete(request.requestId(), HttpStatus.CREATED.value(), body);
        return new Outcome<>(HttpStatus.CREATED.value(), body);
    }

    /**
     * 撤回维护窗口：仅 ACTIVE 且未被封存快照覆盖的窗口可撤回；已封存窗口只能追加更正。
     */
    @Transactional
    public Outcome<WindowResponse> withdrawWindow(String windowId, WithdrawWindowRequest request) {
        String fingerprint = idempotencySupport.fingerprint("WINDOW_WITHDRAW", windowId);
        IdempotencySupport.Replay<WindowResponse> replayed =
                idempotencySupport.checkReplay(request.requestId(), fingerprint, WindowResponse.class);
        if (replayed != null) {
            return new Outcome<>(replayed.status(), replayed.body());
        }
        IdempotencySupport.Replay<WindowResponse> concurrent = idempotencySupport.insertPlaceholder(
                request.requestId(), fingerprint, "WINDOW_WITHDRAW", WindowResponse.class);
        if (concurrent != null) {
            return new Outcome<>(concurrent.status(), concurrent.body());
        }

        MaintenanceWindowRecord window = stationRepository.findWindow(windowId)
                .orElseThrow(() -> ApiException.notFound("window not found: " + windowId));
        stationRepository.findStationForUpdate(window.stationId())
                .orElseThrow(() -> ApiException.notFound("station not found: " + window.stationId()));
        if (!MaintenanceWindowRecord.STATUS_ACTIVE.equals(window.status())) {
            throw ApiException.conflict(
                    "only ACTIVE window can be withdrawn, current status: " + window.status(), null,
                    Map.of("windowId", windowId, "currentStatus", window.status(),
                            "requiredStatus", MaintenanceWindowRecord.STATUS_ACTIVE));
        }
        if (stationRepository.isWindowSealed(windowId)) {
            throw ApiException.conflict(
                    "window is sealed by merge snapshot and cannot be withdrawn; append a correction instead: "
                            + windowId, null,
                    Map.of("windowId", windowId, "sealed", true));
        }
        stationRepository.updateWindowStatus(windowId, MaintenanceWindowRecord.STATUS_WITHDRAWN);
        MaintenanceWindowRecord withdrawn = stationRepository.findWindow(windowId)
                .orElseThrow(() -> new IllegalStateException("window missing after withdraw: " + windowId));
        WindowResponse body = WindowResponse.of(withdrawn, false);
        idempotencySupport.complete(request.requestId(), HttpStatus.OK.value(), body);
        return new Outcome<>(HttpStatus.OK.value(), body);
    }

    /**
     * 查询站点全部维护窗口（历史），按开始时刻稳定排序；站点不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<WindowResponse> listWindows(String stationId) {
        stationRepository.findStation(stationId)
                .orElseThrow(() -> ApiException.notFound("station not found: " + stationId));
        return stationRepository.findWindowsByStation(stationId).stream()
                .map(w -> WindowResponse.of(w, stationRepository.isWindowSealed(w.windowId())))
                .toList();
    }

    /**
     * 查询单个维护窗口明细（含封存标记）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public WindowResponse getWindow(String windowId) {
        MaintenanceWindowRecord window = stationRepository.findWindow(windowId)
                .orElseThrow(() -> ApiException.notFound("window not found: " + windowId));
        return WindowResponse.of(window, stationRepository.isWindowSealed(windowId));
    }

    /**
     * 维护窗口诊断：各状态窗口数量与封存窗口数（均为实际值）。
     */
    @Transactional(readOnly = true)
    public WindowDiagnosticsResponse windowDiagnostics(String stationId) {
        StationRecord station = stationRepository.findStation(stationId)
                .orElseThrow(() -> ApiException.notFound("station not found: " + stationId));
        List<WindowResponse> windows = stationRepository.findWindowsByStation(stationId).stream()
                .map(w -> WindowResponse.of(w, stationRepository.isWindowSealed(w.windowId())))
                .toList();
        int active = 0;
        int superseded = 0;
        int withdrawn = 0;
        int sealed = 0;
        for (WindowResponse window : windows) {
            switch (window.status()) {
                case MaintenanceWindowRecord.STATUS_ACTIVE -> active++;
                case MaintenanceWindowRecord.STATUS_SUPERSEDED -> superseded++;
                case MaintenanceWindowRecord.STATUS_WITHDRAWN -> withdrawn++;
                default -> {
                }
            }
            if (window.sealed()) {
                sealed++;
            }
        }
        return new WindowDiagnosticsResponse(stationId, station.activatedAtUtc(), windows.size(),
                active, superseded, withdrawn, sealed, windows);
    }

    // ---------- 站点观测批量提交 ----------

    /**
     * 批量提交站点观测：先校验完整最终状态（站点引用、簇未合并、提交标识唯一），
     * 再在单事务内逐条按提交时刻匹配维护窗口并写入维护标记；任一失败整批回滚。
     */
    @Transactional
    public Outcome<BatchSubmissionResponse> submitBatch(BatchSubmissionRequest request) {
        String fingerprint = idempotencySupport.fingerprint("BATCH_SUBMIT",
                idempotencySupport.canonicalJson(request));
        // 批量响应可能超过 request_log.response_body 容量：重放时从不可变的 station_observation 重建。
        Outcome<BatchSubmissionResponse> replayed = batchReplayOrPlaceholder(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }

        // 完整校验（不写入）：批内 submissionId 唯一、库内未占用、站点均已登记、簇均未合并。
        Set<String> seen = new HashSet<>();
        for (BatchSubmissionRequest.Item item : request.items()) {
            if (!seen.add(item.submissionId())) {
                throw ApiException.badRequest("duplicate submissionId within batch: " + item.submissionId());
            }
        }
        for (BatchSubmissionRequest.Item item : request.items()) {
            if (observationRepository.findObservation(item.submissionId()).isPresent()) {
                throw ApiException.conflict("submission already exists: " + item.submissionId(), null);
            }
        }
        List<String> stationIds = request.items().stream()
                .map(BatchSubmissionRequest.Item::stationId).distinct().sorted().toList();
        Map<String, StationRecord> stations = new LinkedHashMap<>();
        List<String> missingStations = new ArrayList<>();
        for (String stationId : stationIds) {
            // 按站点标识排序加锁，串行化窗口变更与观测提交，避免死锁
            stationRepository.findStationForUpdate(stationId)
                    .ifPresentOrElse(station -> stations.put(stationId, station),
                            () -> missingStations.add(stationId));
        }
        if (!missingStations.isEmpty()) {
            throw ApiException.notFound(
                    "stations not found: " + String.join(", ", missingStations));
        }
        List<String> mergedClusters = new ArrayList<>();
        for (String clusterId : request.items().stream()
                .map(BatchSubmissionRequest.Item::clusterId).distinct().sorted().toList()) {
            if (observationRepository.findSnapshot(clusterId).isPresent()) {
                mergedClusters.add(clusterId);
            }
        }
        if (!mergedClusters.isEmpty()) {
            throw ApiException.conflict(
                    "clusters already merged, cannot accept new submissions: " + String.join(", ", mergedClusters),
                    null, Map.of("mergedClusterIds", mergedClusters));
        }

        // 校验通过后逐条落库：提交时刻全批统一，逐条匹配 ACTIVE 维护窗口（左闭右开）。
        Instant submittedAt = Instant.now(clock);
        List<StationObservationResponse> items = new ArrayList<>();
        int affectedCount = 0;
        int seq = 0;
        for (BatchSubmissionRequest.Item item : request.items()) {
            MaintenanceWindowRecord window = stationRepository
                    .findActiveWindowCovering(item.stationId(), submittedAt).orElse(null);
            String flag = window == null
                    ? StationObservationRecord.FLAG_NONE
                    : StationObservationRecord.FLAG_MAINTENANCE_AFFECTED;
            if (window != null) {
                affectedCount++;
            }
            StationObservationRecord record = new StationObservationRecord(item.submissionId(), item.clusterId(),
                    item.stationId(), item.observedAtUtc(), submittedAt, item.location(), item.reading(), item.note(),
                    flag, window == null ? null : window.windowId(),
                    window == null ? null : window.windowVersion(),
                    request.requestId(), seq);
            try {
                observationRepository.insertObservation(record);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("submission already exists: " + item.submissionId(), null);
            }
            items.add(StationObservationResponse.of(record));
            seq++;
        }
        BatchSubmissionResponse body = new BatchSubmissionResponse(request.requestId(), submittedAt,
                items.size(), affectedCount, List.copyOf(items));
        idempotencySupport.complete(request.requestId(), HttpStatus.CREATED.value(),
                Map.of("replayType", "BATCH_SUBMIT"));
        return new Outcome<>(HttpStatus.CREATED.value(), body);
    }

    /**
     * 批量提交的幂等检查与占位：同参重放时从不可变 station_observation 重建完整批量响应；
     * 异参 409；无记录时占位后返回 null 继续执行。
     */
    private Outcome<BatchSubmissionResponse> batchReplayOrPlaceholder(String requestId, String fingerprint) {
        var existing = idempotencySupport.findEntry(requestId);
        if (existing.isPresent()) {
            if (!existing.get().fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
            }
            return new Outcome<>(existing.get().responseStatus(), rebuildBatchResponse(requestId));
        }
        var concurrent = idempotencySupport.insertPlaceholder(
                requestId, fingerprint, "BATCH_SUBMIT", Object.class);
        if (concurrent == null) {
            return null;
        }
        return new Outcome<>(concurrent.status(), rebuildBatchResponse(requestId));
    }

    /**
     * 从已落库的不可变观测记录重建批量提交响应（数量与维护标记均为提交时固化值）。
     */
    private BatchSubmissionResponse rebuildBatchResponse(String requestId) {
        List<StationObservationRecord> records =
                observationRepository.findObservationsByRequestId(requestId);
        if (records.isEmpty()) {
            throw new IllegalStateException("batch observations missing for replay: " + requestId);
        }
        List<StationObservationResponse> items = records.stream()
                .map(StationObservationResponse::of).toList();
        int affected = (int) records.stream()
                .filter(r -> StationObservationRecord.FLAG_MAINTENANCE_AFFECTED.equals(r.maintenanceFlag()))
                .count();
        return new BatchSubmissionResponse(requestId, records.get(0).submittedAtUtc(),
                records.size(), affected, items);
    }

    /**
     * 查询单条站点观测原始记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public StationObservationResponse getObservation(String submissionId) {
        return StationObservationResponse.of(observationRepository.findObservation(submissionId)
                .orElseThrow(() -> ApiException.notFound("submission not found: " + submissionId)));
    }

    // ---------- 冲突簇合并与人工裁决 ----------

    /**
     * 自动合并冲突簇：维护受影响观测不可作为胜出版本；簇内仅含受影响观测时结果为
     * PENDING_MANUAL（待人工裁决），不任意选择胜出。每个簇只允许一次自动合并。
     * 胜出规则：未受影响观测中观测时刻最新者胜出，并列时提交标识字典序小者胜出（确定性规则）。
     */
    @Transactional
    public Outcome<MergeResultResponse> mergeCluster(String clusterId, MergeClusterRequest request) {
        String fingerprint = idempotencySupport.fingerprint("CLUSTER_MERGE", clusterId);
        IdempotencySupport.Replay<MergeResultResponse> replayed =
                idempotencySupport.checkReplay(request.requestId(), fingerprint, MergeResultResponse.class);
        if (replayed != null) {
            return new Outcome<>(replayed.status(), replayed.body());
        }
        IdempotencySupport.Replay<MergeResultResponse> concurrent = idempotencySupport.insertPlaceholder(
                request.requestId(), fingerprint, "CLUSTER_MERGE", MergeResultResponse.class);
        if (concurrent != null) {
            return new Outcome<>(concurrent.status(), concurrent.body());
        }

        List<StationObservationRecord> observations = observationRepository.findObservationsByCluster(clusterId);
        if (observations.isEmpty()) {
            throw ApiException.notFound("cluster not found or has no observations: " + clusterId);
        }
        if (observationRepository.findSnapshot(clusterId).isPresent()) {
            throw ApiException.conflict("cluster already merged: " + clusterId, null);
        }

        List<StationObservationRecord> eligible = observations.stream()
                .filter(o -> StationObservationRecord.FLAG_NONE.equals(o.maintenanceFlag()))
                .toList();
        int affectedCount = observations.size() - eligible.size();
        String status;
        String winnerId;
        if (eligible.isEmpty()) {
            status = MergeSnapshotRecord.STATUS_PENDING_MANUAL;
            winnerId = null;
        } else {
            status = MergeSnapshotRecord.STATUS_MERGED;
            winnerId = eligible.stream()
                    .max(Comparator.comparing(StationObservationRecord::observedAtUtc)
                            .thenComparing(Comparator.comparing(StationObservationRecord::submissionId)
                                    .reversed()))
                    .map(StationObservationRecord::submissionId)
                    .orElseThrow();
        }
        List<MergeMarkEntry> marks = observations.stream().map(MergeMarkEntry::of).toList();
        MergeSnapshotRecord snapshot = new MergeSnapshotRecord(clusterId, status, winnerId,
                observations.size(), affectedCount, eligible.size(), writeMarks(marks),
                request.requestId(), Instant.now(clock));
        try {
            observationRepository.insertSnapshot(snapshot);
        } catch (DuplicateKeyException e) {
            // 并发合并同一冲突簇：由主键串行化，后到者按已合并处理
            throw ApiException.conflict("cluster already merged: " + clusterId, null);
        }
        MergeResultResponse body = new MergeResultResponse(clusterId, status, winnerId,
                snapshot.totalCount(), snapshot.affectedCount(), snapshot.eligibleCount(),
                marks, snapshot.mergedAtUtc());
        idempotencySupport.complete(request.requestId(), HttpStatus.OK.value(), body);
        return new Outcome<>(HttpStatus.OK.value(), body);
    }

    /**
     * 人工裁决：可选择维护受影响观测，必须写入理由和裁决人；裁决记录不可变追加。
     * adjudicationId 全局唯一，同参重放、异参 409。
     */
    @Transactional
    public Outcome<AdjudicationResponse> adjudicate(String clusterId, AdjudicateRequest request) {
        String fingerprint = idempotencySupport.fingerprint("CLUSTER_ADJUDICATE", clusterId,
                request.adjudicationId(), request.chosenSubmissionId(), request.reason(), request.adjudicator());
        IdempotencySupport.Replay<AdjudicationResponse> replayed =
                idempotencySupport.checkReplay(request.requestId(), fingerprint, AdjudicationResponse.class);
        if (replayed != null) {
            return new Outcome<>(replayed.status(), replayed.body());
        }
        IdempotencySupport.Replay<AdjudicationResponse> concurrent = idempotencySupport.insertPlaceholder(
                request.requestId(), fingerprint, "CLUSTER_ADJUDICATE", AdjudicationResponse.class);
        if (concurrent != null) {
            return new Outcome<>(concurrent.status(), concurrent.body());
        }

        List<StationObservationRecord> observations = observationRepository.findObservationsByCluster(clusterId);
        if (observations.isEmpty()) {
            throw ApiException.notFound("cluster not found or has no observations: " + clusterId);
        }

        // adjudicationId 重放判定：同参返回原结果，异参 409。
        AdjudicationRecord existing = observationRepository.findAdjudication(request.adjudicationId()).orElse(null);
        if (existing != null) {
            if (!existing.clusterId().equals(clusterId)
                    || !existing.chosenSubmissionId().equals(request.chosenSubmissionId())
                    || !existing.reason().equals(request.reason())
                    || !existing.adjudicator().equals(request.adjudicator())) {
                throw ApiException.conflict(
                        "adjudicationId reused with different parameters: " + request.adjudicationId(), null);
            }
            AdjudicationResponse body = adjudicationResponseOf(existing, observations);
            idempotencySupport.complete(request.requestId(), HttpStatus.OK.value(), body);
            return new Outcome<>(HttpStatus.OK.value(), body);
        }

        StationObservationRecord chosen = observations.stream()
                .filter(o -> o.submissionId().equals(request.chosenSubmissionId()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest(
                        "chosen submission does not belong to cluster: " + request.chosenSubmissionId(),
                        Map.of("clusterId", clusterId, "chosenSubmissionId", request.chosenSubmissionId())));
        AdjudicationRecord record = new AdjudicationRecord(request.adjudicationId(), clusterId,
                request.chosenSubmissionId(), request.reason(), request.adjudicator(),
                request.requestId(), Instant.now(clock));
        try {
            observationRepository.insertAdjudication(record);
        } catch (DuplicateKeyException e) {
            AdjudicationRecord winner = observationRepository.findAdjudication(request.adjudicationId())
                    .orElseThrow(() -> ApiException.conflict(
                            "adjudicationId conflict: " + request.adjudicationId(), null));
            if (!winner.clusterId().equals(clusterId)
                    || !winner.chosenSubmissionId().equals(request.chosenSubmissionId())
                    || !winner.reason().equals(request.reason())
                    || !winner.adjudicator().equals(request.adjudicator())) {
                throw ApiException.conflict(
                        "adjudicationId reused with different parameters: " + request.adjudicationId(), null);
            }
            AdjudicationResponse body = adjudicationResponseOf(winner, observations);
            idempotencySupport.complete(request.requestId(), HttpStatus.OK.value(), body);
            return new Outcome<>(HttpStatus.OK.value(), body);
        }
        AdjudicationResponse body = new AdjudicationResponse(record.adjudicationId(), clusterId,
                record.chosenSubmissionId(), chosen.maintenanceFlag(), record.reason(), record.adjudicator(),
                record.adjudicatedAtUtc(), MergeSnapshotRecord.STATUS_MERGED);
        idempotencySupport.complete(request.requestId(), HttpStatus.CREATED.value(), body);
        return new Outcome<>(HttpStatus.CREATED.value(), body);
    }

    /**
     * 查询冲突簇当前状态：综合合并快照与最近一次人工裁决；簇不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ClusterStateResponse getCluster(String clusterId) {
        int observationCount = observationRepository.countObservationsByCluster(clusterId);
        if (observationCount == 0) {
            throw ApiException.notFound("cluster not found or has no observations: " + clusterId);
        }
        MergeSnapshotRecord snapshot = observationRepository.findSnapshot(clusterId).orElse(null);
        List<AdjudicationRecord> adjudications = observationRepository.findAdjudicationsByCluster(clusterId);
        String status;
        String winnerId;
        if (!adjudications.isEmpty()) {
            status = MergeSnapshotRecord.STATUS_MERGED;
            winnerId = adjudications.get(adjudications.size() - 1).chosenSubmissionId();
        } else if (snapshot != null) {
            status = snapshot.status();
            winnerId = snapshot.winnerSubmissionId();
        } else {
            status = ClusterStateResponse.STATUS_OPEN;
            winnerId = null;
        }
        return new ClusterStateResponse(clusterId, status, winnerId, observationCount, adjudications.size());
    }

    /**
     * 查询冲突簇证据：原始观测（含维护标记）、不可变合并快照与人工裁决历史；只读不改状态。
     */
    @Transactional(readOnly = true)
    public ClusterEvidenceResponse getClusterEvidence(String clusterId) {
        ClusterStateResponse state = getCluster(clusterId);
        List<StationObservationRecord> observations = observationRepository.findObservationsByCluster(clusterId);
        MergeSnapshotRecord snapshot = observationRepository.findSnapshot(clusterId).orElse(null);
        List<AdjudicationRecord> adjudications = observationRepository.findAdjudicationsByCluster(clusterId);
        MergeResultResponse snapshotResponse = snapshot == null ? null
                : new MergeResultResponse(snapshot.clusterId(), snapshot.status(), snapshot.winnerSubmissionId(),
                snapshot.totalCount(), snapshot.affectedCount(), snapshot.eligibleCount(),
                readMarks(snapshot.marksJson()), snapshot.mergedAtUtc());
        List<AdjudicationResponse> adjudicationResponses = adjudications.stream()
                .map(a -> adjudicationResponseOf(a, observations))
                .toList();
        return new ClusterEvidenceResponse(clusterId, state.status(), state.winnerSubmissionId(),
                observations.stream().map(StationObservationResponse::of).toList(),
                snapshotResponse, adjudicationResponses);
    }

    /**
     * 按全局唯一 adjudicationId 查询裁决记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public AdjudicationResponse getAdjudication(String adjudicationId) {
        AdjudicationRecord record = observationRepository.findAdjudication(adjudicationId)
                .orElseThrow(() -> ApiException.notFound("adjudication not found: " + adjudicationId));
        List<StationObservationRecord> observations =
                observationRepository.findObservationsByCluster(record.clusterId());
        return adjudicationResponseOf(record, observations);
    }

    // ---------- 内部辅助 ----------

    /**
     * 校验左闭右开区间合法：开始必须早于结束；不合法时返回实际值与要求。
     */
    private void validateInterval(Instant startUtc, Instant endUtc) {
        if (!startUtc.isBefore(endUtc)) {
            throw ApiException.badRequest("window startUtc must be before endUtc (half-open interval)", Map.of(
                    "startUtc", startUtc.toString(),
                    "endUtc", endUtc.toString(),
                    "requirement", "startUtc < endUtc"));
        }
    }

    /**
     * 校验窗口不早于站点启用时刻；违反时返回启用时刻、请求开始时刻与差额（毫秒）。
     */
    private void validateNotBeforeActivation(StationRecord station, Instant startUtc) {
        if (startUtc.isBefore(station.activatedAtUtc())) {
            long deficitMillis = Duration.between(startUtc, station.activatedAtUtc()).toMillis();
            throw ApiException.badRequest(
                    "window startUtc must not be earlier than station activation time", Map.of(
                            "activatedAtUtc", station.activatedAtUtc().toString(),
                            "requestedStartUtc", startUtc.toString(),
                            "deficitMillis", deficitMillis));
        }
    }

    private String writeMarks(List<MergeMarkEntry> marks) {
        try {
            return objectMapper.writeValueAsString(marks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize merge marks", e);
        }
    }

    private List<MergeMarkEntry> readMarks(String json) {
        try {
            return objectMapper.readValue(json, MARK_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize merge marks", e);
        }
    }

    /**
     * 由裁决记录与簇内观测组装响应（含所选提交的维护标记）。
     */
    private AdjudicationResponse adjudicationResponseOf(AdjudicationRecord record,
                                                        List<StationObservationRecord> observations) {
        String chosenFlag = observations.stream()
                .filter(o -> o.submissionId().equals(record.chosenSubmissionId()))
                .map(StationObservationRecord::maintenanceFlag)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "chosen submission missing for adjudication: " + record.adjudicationId()));
        return new AdjudicationResponse(record.adjudicationId(), record.clusterId(), record.chosenSubmissionId(),
                chosenFlag, record.reason(), record.adjudicator(), record.adjudicatedAtUtc(),
                MergeSnapshotRecord.STATUS_MERGED);
    }

    /**
     * 写操作结果：HTTP 状态码与响应体。
     */
    public record Outcome<T>(int status, T body) {
    }
}
