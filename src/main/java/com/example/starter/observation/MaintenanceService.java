package com.example.starter.observation;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 观测站维护窗口业务服务：站点登记、维护窗口登记/追加更正、观测批量提交、冲突簇合并与人工裁决。
 *
 * <p>顺序裁决：窗口写事务与批量提交事务均先锁站点行（SELECT ... FOR UPDATE），
 * 窗口变更先提交则后续观测按新版本标记；观测先提交则按 submittedAtUtc 时刻已存在的窗口版本折叠匹配并固化。
 *
 * <p>只追加语义：窗口行永不更新或删除，更正/撤回均追加新版本行；观测版本快照固化当时窗口版本与受影响标记，
 * 后续删窗、补录或人工裁决不改写历史快照。
 *
 * <p>幂等：所有写操作复用 request_log 占位机制，同 requestId 同参重放首次结果，异参 409，失败回滚不占键。
 */
@Service
public class MaintenanceService {

    private static final String SEPARATOR = "";

    private final StationRepository stationRepository;
    private final MaintenanceWindowRepository windowRepository;
    private final ObservationSubmissionRepository submissionRepository;
    private final ClusterMergeRepository clusterMergeRepository;
    private final ObservationRepository observationRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MaintenanceService(StationRepository stationRepository,
                              MaintenanceWindowRepository windowRepository,
                              ObservationSubmissionRepository submissionRepository,
                              ClusterMergeRepository clusterMergeRepository,
                              ObservationRepository observationRepository,
                              RequestLogRepository requestLogRepository,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.stationRepository = stationRepository;
        this.windowRepository = windowRepository;
        this.submissionRepository = submissionRepository;
        this.clusterMergeRepository = clusterMergeRepository;
        this.observationRepository = observationRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 站点登记 ----------

    /**
     * 登记观测站及其 UTC 启用时刻；stationId 重复返回 409。
     */
    @Transactional
    public MaintenanceOutcome<StationResponse> registerStation(RegisterStationRequest request) {
        String fingerprint = fingerprint("STATION_REGISTER", request.requestId(), request.stationId(),
                request.activatedAtUtc().toString());
        MaintenanceOutcome<StationResponse> replayed = checkReplay(request.requestId(), fingerprint,
                StationResponse.class);
        if (replayed != null) {
            return replayed;
        }
        MaintenanceOutcome<StationResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "STATION_REGISTER", StationResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        if (stationRepository.findById(request.stationId()).isPresent()) {
            throw ApiException.conflict("station already exists: " + request.stationId(), null);
        }
        try {
            stationRepository.insert(new Station(request.stationId(), request.activatedAtUtc()));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("station already exists: " + request.stationId(), null);
        }
        return complete(request.requestId(), HttpStatus.CREATED,
                new StationResponse(request.stationId(), request.activatedAtUtc()));
    }

    // ---------- 维护窗口登记 ----------

    /**
     * 登记 UTC 左闭右开维护区间：不得早于站点启用时刻，与同站有效窗口重叠不得登记。
     */
    @Transactional
    public MaintenanceOutcome<MaintenanceWindowResponse> registerWindow(RegisterMaintenanceWindowRequest request) {
        String fingerprint = fingerprint("WINDOW_REGISTER", request.requestId(), request.windowId(),
                request.stationId(), request.startAtUtc().toString(), request.endAtUtc().toString());
        MaintenanceOutcome<MaintenanceWindowResponse> replayed = checkReplay(request.requestId(), fingerprint,
                MaintenanceWindowResponse.class);
        if (replayed != null) {
            return replayed;
        }
        MaintenanceOutcome<MaintenanceWindowResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "WINDOW_REGISTER",
                        MaintenanceWindowResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        Station station = lockStation(request.stationId());
        validateRange(request.startAtUtc(), request.endAtUtc(), station.activatedAtUtc());
        if (windowRepository.findByWindowId(request.windowId()).isPresent()) {
            throw ApiException.conflict("maintenance window id already exists: " + request.windowId(), null);
        }
        List<MaintenanceWindow> rows = windowRepository.findAllByStationForUpdate(request.stationId());
        List<MaintenanceWindow> effective = EffectiveMaintenanceWindows.fold(rows);
        rejectIfOverlaps(effective, request.startAtUtc(), request.endAtUtc(), null);

        int version = windowRepository.findMaxVersion(request.stationId()) + 1;
        MaintenanceWindow window = new MaintenanceWindow(request.windowId(), request.stationId(), version,
                MaintenanceWindowKind.REGISTER, request.startAtUtc(), request.endAtUtc(), null, false,
                Instant.now(clock));
        windowRepository.insert(window);
        return complete(request.requestId(), HttpStatus.CREATED, MaintenanceWindowResponse.of(window));
    }

    /**
     * 追加更正窗口：替换既有有效窗口的时间区间，或撤回（retract）既有窗口；历史窗口行永不修改。
     */
    @Transactional
    public MaintenanceOutcome<MaintenanceWindowResponse> correctWindow(CorrectMaintenanceWindowRequest request) {
        String fingerprint = fingerprint("WINDOW_CORRECTION", request.requestId(), request.windowId(),
                request.stationId(), request.supersedesWindowId(), String.valueOf(request.retract()),
                request.startAtUtc() == null ? "<null>" : request.startAtUtc().toString(),
                request.endAtUtc() == null ? "<null>" : request.endAtUtc().toString());
        MaintenanceOutcome<MaintenanceWindowResponse> replayed = checkReplay(request.requestId(), fingerprint,
                MaintenanceWindowResponse.class);
        if (replayed != null) {
            return replayed;
        }
        MaintenanceOutcome<MaintenanceWindowResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "WINDOW_CORRECTION",
                        MaintenanceWindowResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        Station station = lockStation(request.stationId());
        if (windowRepository.findByWindowId(request.windowId()).isPresent()) {
            throw ApiException.conflict("maintenance window id already exists: " + request.windowId(), null);
        }
        MaintenanceWindow target = windowRepository.findByWindowId(request.supersedesWindowId())
                .orElseThrow(() -> ApiException.notFound(
                        "target maintenance window not found: " + request.supersedesWindowId()));
        if (!target.stationId().equals(request.stationId())) {
            // 不跨站更正：按目标不存在处理，避免探测其他站点窗口
            throw ApiException.notFound(
                    "target maintenance window not found: " + request.supersedesWindowId());
        }
        List<MaintenanceWindow> rows = windowRepository.findAllByStationForUpdate(request.stationId());
        Map<String, MaintenanceWindow> effectiveById = new LinkedHashMap<>();
        for (MaintenanceWindow window : EffectiveMaintenanceWindows.fold(rows)) {
            effectiveById.put(window.windowId(), window);
        }
        if (!effectiveById.containsKey(target.windowId())) {
            // 已封存快照覆盖的历史窗口与已被更正/撤回的窗口均不可修改，只能对当前有效窗口追加更正
            throw ApiException.badRequest(
                    "historical maintenance window cannot be modified, only append a correction to an "
                            + "effective window: " + target.windowId());
        }

        Instant start = request.startAtUtc();
        Instant end = request.endAtUtc();
        if (Boolean.TRUE.equals(request.retract())) {
            if (start != null || end != null) {
                throw ApiException.badRequest("retracting correction must not carry a time range");
            }
        } else {
            if (start == null || end == null) {
                throw ApiException.badRequest(
                        "non-retracting correction must carry both startAtUtc and endAtUtc");
            }
            validateRange(start, end, station.activatedAtUtc());
            rejectIfOverlaps(new ArrayList<>(effectiveById.values()), start, end, target.windowId());
        }

        int version = windowRepository.findMaxVersion(request.stationId()) + 1;
        MaintenanceWindow correction = new MaintenanceWindow(request.windowId(), request.stationId(), version,
                MaintenanceWindowKind.CORRECTION, start, end, target.windowId(),
                Boolean.TRUE.equals(request.retract()), Instant.now(clock));
        windowRepository.insert(correction);
        return complete(request.requestId(), HttpStatus.OK, MaintenanceWindowResponse.of(correction));
    }

    // ---------- 观测批量提交 ----------

    /**
     * 批量提交：先逐条核对整批最终状态（站点引用、提交标识、窗口匹配），全部通过后一次事务写入；
     * 任一观测格式（控制器校验）或站点引用失败整批回滚，不留半成品。
     * 逐条按 submittedAtUtc 时刻已存在的窗口版本匹配并完整写入固化标记。
     */
    @Transactional
    public MaintenanceOutcome<BatchSubmitResponse> submitBatch(SubmitObservationBatchRequest request) {
        String fingerprint = fingerprint("SUBMIT_BATCH", request.requestId(),
                String.valueOf(request.submissions().size()), canonicalItems(request.submissions()));
        MaintenanceOutcome<BatchSubmitResponse> replayed = checkReplay(request.requestId(), fingerprint,
                BatchSubmitResponse.class);
        if (replayed != null) {
            return replayed;
        }
        MaintenanceOutcome<BatchSubmitResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "SUBMIT_BATCH", BatchSubmitResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        List<SubmitObservationBatchRequest.Item> items = request.submissions();
        Set<String> idsInBatch = new HashSet<>();
        for (SubmitObservationBatchRequest.Item item : items) {
            if (!idsInBatch.add(item.submissionId())) {
                throw ApiException.badRequest("duplicate submissionId within batch: " + item.submissionId());
            }
            if (submissionRepository.findById(item.submissionId()).isPresent()) {
                throw ApiException.conflict("submission already exists: " + item.submissionId(), null);
            }
        }

        // 按 stationId 排序后依次加站点行锁，保证多站点混合批次也无死锁、窗口视图稳定
        List<String> stationIds = items.stream().map(SubmitObservationBatchRequest.Item::stationId)
                .distinct().sorted().toList();
        Map<String, List<MaintenanceWindow>> rowsByStation = new LinkedHashMap<>();
        for (String stationId : stationIds) {
            lockStation(stationId);
            rowsByStation.put(stationId, windowRepository.findAllByStationForUpdate(stationId));
        }

        List<ObservationSubmission> submissions = new ArrayList<>();
        int affectedCount = 0;
        for (SubmitObservationBatchRequest.Item item : items) {
            // 站点行锁串行化窗口变更与提交：读到的窗口行即本次提交事务时已存在的版本。
            // 窗口变更先提交则此处可见新版本；观测先提交则后来的窗口变更不可见，按提交时版本固化。
            // 窗口区间匹配观测业务时刻 submittedAtUtc（左闭右开）。
            List<MaintenanceWindow> effectiveAtSubmit =
                    EffectiveMaintenanceWindows.fold(rowsByStation.get(item.stationId()));
            MaintenanceWindow hit = EffectiveMaintenanceWindows.match(effectiveAtSubmit, item.submittedAtUtc());
            boolean affected = hit != null;
            if (affected) {
                affectedCount++;
            }
            submissions.add(new ObservationSubmission(
                    item.submissionId(), item.stationId(), item.observationId(), item.submittedAtUtc(),
                    item.location(), item.reading(), item.note(),
                    affected,
                    hit == null ? null : hit.windowId(),
                    hit == null ? null : hit.version(),
                    SubmissionStatus.PENDING, request.requestId()));
        }

        try {
            for (ObservationSubmission submission : submissions) {
                submissionRepository.insert(submission);
            }
        } catch (DuplicateKeyException e) {
            // 并发提交同一 submissionId：整批回滚，按冲突处理
            throw ApiException.conflict("submission already exists (concurrent batch)", null);
        }

        List<SubmissionResponse> details = submissions.stream().map(SubmissionResponse::of).toList();
        return complete(request.requestId(), HttpStatus.OK,
                new BatchSubmitResponse(request.requestId(), submissions.size(), affectedCount, details));
    }

    // ---------- 冲突簇合并 ----------

    /**
     * 对一个观测记录下尚未消费的提交簇执行合并：受影响观测不可作为自动胜出版本；
     * 簇内只含受影响观测时结果为 PENDING_MANUAL（提交置 REVIEW），不任选胜出、不写观测版本。
     * 请求 submissionIds 必须恰好等于该记录全部未消费提交，防止部分簇合并。
     */
    @Transactional
    public MaintenanceOutcome<ClusterMergeResponse> mergeCluster(MergeClusterRequest request) {
        String fingerprint = fingerprint("CLUSTER_MERGE", request.requestId(), request.observationId(),
                canonicalIds(request.submissionIds()));
        MaintenanceOutcome<ClusterMergeResponse> replayed = checkReplay(request.requestId(), fingerprint,
                ClusterMergeResponse.class);
        if (replayed != null) {
            return replayed;
        }
        MaintenanceOutcome<ClusterMergeResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "CLUSTER_MERGE", ClusterMergeResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        // 先锁簇内提交行再锁观测行，与人工裁决的加锁顺序（簇记录→提交行→观测行）保持一致，避免死锁
        List<ObservationSubmission> cluster =
                submissionRepository.findPendingClusterForUpdate(request.observationId());
        if (cluster.isEmpty()) {
            throw ApiException.conflict(
                    "no pending submissions to merge for observation: " + request.observationId(), null);
        }
        if (cluster.stream().anyMatch(submission -> submission.status() == SubmissionStatus.REVIEW)) {
            // 已判定待人工裁决的簇不能再次自动合并；必须走人工裁决
            throw ApiException.conflict(
                    "conflict cluster is already pending manual decision for observation: "
                            + request.observationId(), null);
        }
        ObservationSnapshot current = lockObservationIfPresent(request.observationId());
        List<String> actualIds = cluster.stream().map(ObservationSubmission::submissionId).sorted().toList();
        List<String> requestedIds = request.submissionIds().stream().distinct().sorted().toList();
        if (!actualIds.equals(requestedIds) || requestedIds.size() != request.submissionIds().size()) {
            throw ApiException.badRequest(
                    "submissionIds must cover exactly all pending submissions; required: " + actualIds
                            + ", provided: " + request.submissionIds());
        }

        int affectedCount = (int) cluster.stream().filter(ObservationSubmission::maintenanceAffected).count();
        Instant now = Instant.now(clock);
        Integer baseVersion = current == null ? null : current.version();

        if (affectedCount == cluster.size()) {
            // 冲突簇只含受影响观测：待人工裁决，不任选胜出
            for (ObservationSubmission submission : cluster) {
                submissionRepository.updateStatus(submission.submissionId(), SubmissionStatus.REVIEW);
            }
            ClusterMergeRecord record = new ClusterMergeRecord(
                    request.requestId(), request.observationId(), ClusterMergeState.PENDING_MANUAL,
                    baseVersion, null, false,
                    cluster.stream().map(ObservationSubmission::submissionId).toList(),
                    cluster.size(), affectedCount, null, null, null, null, null, null, now, null);
            clusterMergeRepository.insert(record);
            return complete(request.requestId(), HttpStatus.OK,
                    ClusterMergeResponse.of(record));
        }

        // 自动胜出：未受影响观测中按提交时刻取最新；并列取 submissionId 最小，保证确定性
        ObservationSubmission winner = cluster.stream()
                .filter(submission -> !submission.maintenanceAffected())
                .min((a, b) -> {
                    int byTime = a.submittedAtUtc().compareTo(b.submittedAtUtc());
                    return byTime != 0 ? -byTime : a.submissionId().compareTo(b.submissionId());
                })
                .orElseThrow(() -> new IllegalStateException("non-affected winner expected"));
        MaintenanceOutcome<ClusterMergeResponse> written = applyWinnerToObservation(
                request.requestId(), request.observationId(), current, cluster, winner,
                ClusterMergeState.AUTO_MERGED, null, null, now, null,
                false, null, null);
        return written;
    }

    /**
     * 人工裁决：仅 PENDING_MANUAL 簇可裁决；可选择受影响观测作为胜出，但必须写入裁决人和理由。
     * 胜出提交的维护标记与窗口版本随观测版本固化；裁决不改写既有历史快照。
     */
    @Transactional
    public MaintenanceOutcome<ClusterMergeResponse> decide(ManualDecisionRequest request) {
        String fingerprint = fingerprint("MANUAL_DECISION", request.requestId(), request.mergeId(),
                request.winningSubmissionId(), request.adjudicator(), request.reason());
        MaintenanceOutcome<ClusterMergeResponse> replayed = checkReplay(request.requestId(), fingerprint,
                ClusterMergeResponse.class);
        if (replayed != null) {
            return replayed;
        }
        MaintenanceOutcome<ClusterMergeResponse> concurrent =
                insertPlaceholder(request.requestId(), fingerprint, "MANUAL_DECISION", ClusterMergeResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        ClusterMergeRecord pending = clusterMergeRepository.findByMergeIdForUpdate(request.mergeId())
                .orElseThrow(() -> ApiException.notFound("cluster merge not found: " + request.mergeId()));
        if (pending.state() != ClusterMergeState.PENDING_MANUAL) {
            throw ApiException.conflict(
                    "cluster merge is not pending manual decision, current state: " + pending.state(), null);
        }

        List<ObservationSubmission> cluster =
                submissionRepository.findByIdsForUpdate(pending.submissionIds());
        Map<String, ObservationSubmission> byId = new LinkedHashMap<>();
        for (ObservationSubmission submission : cluster) {
            byId.put(submission.submissionId(), submission);
        }
        if (cluster.size() != pending.submissionIds().size()
                || !byId.keySet().equals(new HashSet<>(pending.submissionIds()))) {
            throw new IllegalStateException("cluster submissions diverged from merge record: " + request.mergeId());
        }
        ObservationSubmission winner = byId.get(request.winningSubmissionId());
        if (winner == null) {
            throw ApiException.badRequest(
                    "winning submission must belong to the conflict cluster; cluster submissions: "
                            + pending.submissionIds());
        }

        ObservationSnapshot current = lockObservationIfPresent(pending.observationId());
        Instant now = Instant.now(clock);
        MaintenanceOutcome<ClusterMergeResponse> written = applyWinnerToObservation(
                request.requestId(), pending.observationId(), current,
                pending.submissionIds().stream().map(byId::get).toList(),
                winner, ClusterMergeState.RESOLVED, request.mergeId(),
                request.requestId(), now, request,
                winner.maintenanceAffected(), winner.windowId(), winner.windowVersion());
        if (written == null) {
            throw new IllegalStateException("decision outcome missing");
        }
        return written;
    }

    // ---------- 查询（只读） ----------

    /**
     * 查询站点；不存在 404。
     */
    @Transactional(readOnly = true)
    public StationResponse getStation(String stationId) {
        return StationResponse.of(stationRepository.findById(stationId)
                .orElseThrow(() -> ApiException.notFound("station not found: " + stationId)));
    }

    /**
     * 查询站点窗口变更历史：包含登记窗口与每条追加更正/撤回行，按维护版本升序；只读。
     */
    @Transactional(readOnly = true)
    public List<MaintenanceWindowResponse> listWindows(String stationId) {
        stationRepository.findById(stationId)
                .orElseThrow(() -> ApiException.notFound("station not found: " + stationId));
        return windowRepository.findAllByStation(stationId).stream()
                .map(MaintenanceWindowResponse::of).toList();
    }

    /**
     * 诊断查询：返回当前（查询时）有效窗口集合，以及给定业务时刻（UTC，左闭右开）命中的窗口；只读。
     * 用于核对某次观测的业务时刻在当前窗口版本下会命中哪个窗口；
     * 历史提交的固化标记以 observation_submission 中按提交时版本保存的值为准，不随后续窗口变更改变。
     */
    @Transactional(readOnly = true)
    public MaintenanceEffectiveAtResponse effectiveWindowsAt(String stationId, Instant atUtc) {
        stationRepository.findById(stationId)
                .orElseThrow(() -> ApiException.notFound("station not found: " + stationId));
        List<MaintenanceWindow> effectiveFolded =
                EffectiveMaintenanceWindows.fold(windowRepository.findAllByStation(stationId));
        List<MaintenanceWindowResponse> effective = effectiveFolded.stream()
                .map(MaintenanceWindowResponse::of)
                .sorted((a, b) -> Integer.compare(a.version(), b.version()))
                .toList();
        MaintenanceWindow hit = EffectiveMaintenanceWindows.match(effectiveFolded, atUtc);
        return new MaintenanceEffectiveAtResponse(stationId, atUtc, effective.size(), effective,
                hit == null ? null : hit.windowId());
    }

    /**
     * 证据查询：按提交标识返回原始观测与固化维护标记；不存在 404。只读。
     */
    @Transactional(readOnly = true)
    public SubmissionResponse getSubmission(String submissionId) {
        return SubmissionResponse.of(submissionRepository.findById(submissionId)
                .orElseThrow(() -> ApiException.notFound("submission not found: " + submissionId)));
    }

    /**
     * 批次明细查询：按批量提交 requestId 返回逐条结果（含维护标记）；批次不存在 404。只读。
     */
    @Transactional(readOnly = true)
    public BatchSubmitResponse getBatch(String requestId) {
        List<ObservationSubmission> submissions = submissionRepository.findByBatchRequestId(requestId);
        if (submissions.isEmpty()) {
            throw ApiException.notFound("batch not found: " + requestId);
        }
        int affected = (int) submissions.stream().filter(ObservationSubmission::maintenanceAffected).count();
        return new BatchSubmitResponse(requestId, submissions.size(), affected,
                submissions.stream().map(SubmissionResponse::of).toList());
    }

    /**
     * 按观测记录查询冲突簇合并历史，按提交完成时刻先后排序；只读。
     */
    @Transactional(readOnly = true)
    public List<ClusterMergeResponse> listClusterMerges(String observationId) {
        return clusterMergeRepository.findByObservationId(observationId).stream()
                .map(ClusterMergeResponse::of).toList();
    }

    /**
     * 按 mergeId 查询单次簇合并/裁决记录；不存在 404。只读。
     */
    @Transactional(readOnly = true)
    public ClusterMergeResponse getClusterMerge(String mergeId) {
        return ClusterMergeResponse.of(clusterMergeRepository.findByMergeId(mergeId)
                .orElseThrow(() -> ApiException.notFound("cluster merge not found: " + mergeId)));
    }

    // ---------- 内部辅助 ----------

    /**
     * 将胜出提交内容写入观测版本（自动合并或人工裁决共用），固化调用方指定的维护标记与窗口版本，
     * 簇内全部提交置 CONSUMED，并落簇合并记录；返回已回填幂等响应的结果。
     */
    private MaintenanceOutcome<ClusterMergeResponse> applyWinnerToObservation(
            String requestId, String observationId, ObservationSnapshot current,
            List<ObservationSubmission> cluster, ObservationSubmission winner,
            ClusterMergeState state, String mergeIdForDecision, String decisionRequestId,
            Instant now, ManualDecisionRequest decision,
            boolean decidedAffected, String decidedWindowId, Integer decidedWindowVersion) {
        if (current != null && current.deleted()) {
            throw ApiException.gone("observation already deleted: " + observationId);
        }
        int baseVersion = current == null ? 0 : current.version();
        boolean contentChanged = current == null
                || !ObjectsEquals.content(current, winner.location(), winner.reading(), winner.note());
        int newVersion = contentChanged ? baseVersion + 1 : baseVersion;
        if (contentChanged) {
            ObservationSnapshot next = new ObservationSnapshot(observationId, newVersion,
                    winner.location(), winner.reading(), winner.note(), false,
                    decidedAffected, decidedWindowId, decidedWindowVersion);
            if (current == null) {
                observationRepository.insertCurrent(next);
            } else {
                observationRepository.updateCurrent(next);
            }
            observationRepository.insertVersion(next);
        }
        for (ObservationSubmission submission : cluster) {
            submissionRepository.updateStatus(submission.submissionId(), SubmissionStatus.CONSUMED);
        }

        List<String> orderedIds = cluster.stream()
                .sorted((a, b) -> {
                    int byTime = a.submittedAtUtc().compareTo(b.submittedAtUtc());
                    return byTime != 0 ? byTime : a.submissionId().compareTo(b.submissionId());
                })
                .map(ObservationSubmission::submissionId)
                .toList();
        int affectedCount = (int) cluster.stream().filter(ObservationSubmission::maintenanceAffected).count();
        ClusterMergeRecord record;
        if (state == ClusterMergeState.AUTO_MERGED) {
            record = new ClusterMergeRecord(
                    requestId, observationId, ClusterMergeState.AUTO_MERGED,
                    current == null ? null : current.version(), newVersion, contentChanged,
                    orderedIds, cluster.size(), affectedCount, winner.submissionId(),
                    false, null, null, null, null, now, null);
            clusterMergeRepository.insert(record);
        } else {
            // PENDING_MANUAL 行在裁决事务内流转；以状态条件更新兜底并发双裁决
            int updated = clusterMergeRepository.applyDecision(mergeIdForDecision, winner.submissionId(),
                    newVersion, contentChanged, decidedAffected, decidedWindowId, decidedWindowVersion,
                    decision.adjudicator(), decision.reason(), now);
            if (updated != 1) {
                ClusterMergeState latest = clusterMergeRepository.findByMergeId(mergeIdForDecision)
                        .map(ClusterMergeRecord::state)
                        .orElseThrow(() -> new IllegalStateException("cluster merge vanished: " + mergeIdForDecision));
                throw ApiException.conflict(
                        "cluster merge decision lost concurrency race, current state: " + latest, null);
            }
            record = clusterMergeRepository.findByMergeId(mergeIdForDecision)
                    .orElseThrow(() -> new IllegalStateException("cluster merge vanished: " + mergeIdForDecision));
        }
        return complete(requestId, HttpStatus.OK, ClusterMergeResponse.of(record));
    }

    /**
     * 锁站点行并返回站点；站点引用不存在由 404 明确区分（批量提交据此整批回滚）。
     */
    private Station lockStation(String stationId) {
        return stationRepository.findByIdForUpdate(stationId)
                .orElseThrow(() -> ApiException.notFound("station not found: " + stationId));
    }

    /**
     * 观测存在则锁其当前行（与常规三方合并/删除/解决互斥），不存在返回 null。
     */
    private ObservationSnapshot lockObservationIfPresent(String observationId) {
        return observationRepository.findCurrentForUpdate(observationId).orElse(null);
    }

    /**
     * 区间合法性：必须为正时长大区间，且开始时刻不得早于站点启用时刻（左闭右开，相等允许）。
     */
    private void validateRange(Instant start, Instant end, Instant activatedAtUtc) {
        if (!end.isAfter(start)) {
            throw ApiException.badRequest(
                    "maintenance window must have positive duration; actual start: " + start + ", end: " + end
                            + ", required: end > start");
        }
        if (start.isBefore(activatedAtUtc)) {
            throw ApiException.badRequest(
                    "maintenance window must not start before station activation; actual start: " + start
                            + ", required >= activation: " + activatedAtUtc
                            + ", difference seconds: "
                            + java.time.Duration.between(start, activatedAtUtc).getSeconds());
        }
    }

    /**
     * 与同站有效窗口重叠则拒绝（左闭右开，端点相接不算重叠）；ignoreWindowId 为更正时被替换的目标窗口。
     */
    private void rejectIfOverlaps(List<MaintenanceWindow> effective, Instant start, Instant end,
                                  String ignoreWindowId) {
        for (MaintenanceWindow window : effective) {
            if (window.windowId().equals(ignoreWindowId)) {
                continue;
            }
            if (EffectiveMaintenanceWindows.overlaps(start, end, window.startAtUtc(), window.endAtUtc())) {
                throw ApiException.conflict(
                        "maintenance window overlaps with effective window: " + window.windowId()
                                + " [" + window.startAtUtc() + ", " + window.endAtUtc()
                                + "); new window [" + start + ", " + end + ")", null);
            }
        }
    }

    private String canonicalItems(List<SubmitObservationBatchRequest.Item> items) {
        List<List<String>> rows = items.stream()
                .map(item -> List.of(item.submissionId(), item.stationId(), item.observationId(),
                        item.submittedAtUtc().toString(), item.location(), item.reading(), item.note()))
                .toList();
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize batch items", e);
        }
    }

    private String canonicalIds(List<String> ids) {
        return ids.stream().sorted().reduce("", (acc, id) -> acc + id + ",");
    }

    // ---------- 幂等（与 ObservationService 同语义，按本服务响应类型回放） ----------

    private <T> MaintenanceOutcome<T> checkReplay(String requestId, String fingerprint, Class<T> bodyType) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return new MaintenanceOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
    }

    private <T> MaintenanceOutcome<T> insertPlaceholder(String requestId, String fingerprint,
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
            return new MaintenanceOutcome<>(entry.responseStatus(), readBody(entry.responseBody(), bodyType));
        }
    }

    private <T> MaintenanceOutcome<T> complete(String requestId, HttpStatus status, T body) {
        try {
            requestLogRepository.complete(requestId, status.value(), objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
        return new MaintenanceOutcome<>(status.value(), body);
    }

    private <T> T readBody(String json, Class<T> bodyType) {
        try {
            return objectMapper.readValue(json, bodyType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
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
    public record MaintenanceOutcome<T>(int status, T body) {
    }

    /**
     * 观测版本内容比较（读数按数值，其余按原文），供簇合并判断是否产生新版本。
     */
    private static final class ObjectsEquals {

        private static boolean content(ObservationSnapshot current, String location, String reading, String note) {
            return java.util.Objects.equals(current.location(), location)
                    && java.util.Objects.equals(current.note(), note)
                    && new java.math.BigDecimal(current.reading()).compareTo(new java.math.BigDecimal(reading)) == 0;
        }
    }
}
