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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 观测采样许可业务服务：许可创建/提前结束/撤销、采样提交、批量离线合并与冲突簇裁决的许可裁决。
 *
 * <p>所有写操作都在单事务内，先在 {@code request_log} 占位（失败回滚不占键），再对涉及设备的
 * {@code permit_device_lock} 行加锁串行化同设备裁决，随后对许可行与观测行加行锁完成判定与变更。
 *
 * <p>许可命中要求设备、采集网格、采集时刻<strong>唯一</strong>命中一张 ACTIVE 许可；采样接收只扣减该许可计数。
 * 批量离线合并先按“完整最终计数 + 坐标边界 + 冲突簇”整体预校验，任一许可类失败抛 422，整批回滚不留半成品。
 * requestId 幂等指纹由请求参数决定；返回给客户端的 permitKey 额外含命中许可的 permitVersion，
 * 同 requestId 同参重放原结果（含原 permitKey），失败不占键。
 */
@Service
public class SamplingService {

    private static final String SEPARATOR = "";
    private static final List<String> FIELDS = List.of("location", "reading", "note");

    private final SamplingPermitRepository permitRepository;
    private final ObservationRepository observationRepository;
    private final MergeClusterRepository clusterRepository;
    private final RequestLogRepository requestLogRepository;
    private final ResolutionRepository resolutionRepository;
    private final PermitSampleKeyRepository sampleKeyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SamplingService(SamplingPermitRepository permitRepository,
                           ObservationRepository observationRepository,
                           MergeClusterRepository clusterRepository,
                           RequestLogRepository requestLogRepository,
                           ResolutionRepository resolutionRepository,
                           PermitSampleKeyRepository sampleKeyRepository,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.permitRepository = permitRepository;
        this.observationRepository = observationRepository;
        this.clusterRepository = clusterRepository;
        this.requestLogRepository = requestLogRepository;
        this.resolutionRepository = resolutionRepository;
        this.sampleKeyRepository = sampleKeyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 许可管理 ----------

    /**
     * 创建采样许可：禁止追溯起点；同设备有效许可在网格∩时间交集重叠时返回 409（PERMIT_OVERLAP）。
     */
    @Transactional
    public PermitOutcome createPermit(CreatePermitRequest request) {
        Instant now = Instant.now(clock);
        if (request.gridMin() > request.gridMax()) {
            throw ApiException.badRequest("gridMin must be less than or equal to gridMax");
        }
        if (!PermitRules.isValidCreateRange(request.validFromUtc(), request.validToUtc(), now)) {
            throw ApiException.unprocessable(
                    "permit range invalid: validFromUtc must be >= now and before validToUtc",
                    PermitRejectReason.INVALID_PERMIT_RANGE);
        }
        String fingerprint = fingerprint("PERMIT_CREATE", request.permitId(), request.deviceId(),
                request.permitVersion(), String.valueOf(request.gridMin()), String.valueOf(request.gridMax()),
                request.validFromUtc().toString(), request.validToUtc().toString(),
                String.valueOf(request.maxObservations()));
        PermitOutcome replayed = checkReplay(request.requestId(), fingerprint, PermitResponse.class);
        if (replayed != null) {
            return replayed;
        }
        PermitOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "PERMIT_CREATE",
                PermitResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        if (permitRepository.findById(request.permitId()).isPresent()) {
            throw ApiException.conflict("permit already exists: " + request.permitId(), null);
        }

        permitRepository.lockDevice(request.deviceId());
        List<SamplingPermit> devicePermits = permitRepository.findByDeviceForUpdate(request.deviceId());
        for (SamplingPermit existing : devicePermits) {
            if (PermitRules.overlaps(existing, request.gridMin(), request.gridMax(),
                    request.validFromUtc(), request.validToUtc())) {
                throw ApiException.permitConflict(
                        "sampling permit overlaps an active permit of device '" + request.deviceId()
                                + "' in grid and time: " + existing.permitId(),
                        PermitRejectReason.PERMIT_OVERLAP);
            }
        }
        SamplingPermit permit = new SamplingPermit(
                request.permitId(), request.deviceId(), request.permitVersion(),
                request.gridMin(), request.gridMax(), request.validFromUtc(), request.validToUtc(),
                null, null, PermitStatus.ACTIVE, request.maxObservations(), 0);
        try {
            permitRepository.insert(permit);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("permit already exists: " + request.permitId(), null);
        }
        return completePermit(request.requestId(), HttpStatus.CREATED, permit);
    }

    /**
     * 提前结束许可：只影响 terminatedAtUtc（不含）之后的采样，已接收观测与计数不变。
     */
    @Transactional
    public PermitOutcome terminatePermit(String permitId, TerminatePermitRequest request) {
        String fingerprint = fingerprint("PERMIT_TERMINATE", permitId, request.terminatedAtUtc().toString());
        PermitOutcome replayed = checkReplay(request.requestId(), fingerprint, PermitResponse.class);
        if (replayed != null) {
            return replayed;
        }
        PermitOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "PERMIT_TERMINATE",
                PermitResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        SamplingPermit permit = lockPermit(permitId);
        if (permit.status() != PermitStatus.ACTIVE) {
            throw ApiException.unprocessable("permit is not active: " + permitId,
                    PermitRejectReason.PERMIT_NOT_ACTIVE);
        }
        Instant now = Instant.now(clock);
        Instant terminatedAt = request.terminatedAtUtc();
        if (terminatedAt.isBefore(now)) {
            throw ApiException.unprocessable(
                    "terminatedAtUtc must not be in the past: " + terminatedAt,
                    PermitRejectReason.INVALID_PERMIT_RANGE);
        }
        if (terminatedAt.isBefore(permit.validFromUtc()) || terminatedAt.isAfter(permit.validToUtc())) {
            throw ApiException.unprocessable(
                    "terminatedAtUtc must be within [validFromUtc, validToUtc]",
                    PermitRejectReason.INVALID_PERMIT_RANGE);
        }
        // 已存在更早的提前结束时刻时，结束只能前移不能放宽，避免重新开放已关闭窗口
        if (permit.terminatedAtUtc() != null && terminatedAt.isAfter(permit.terminatedAtUtc())) {
            throw ApiException.unprocessable(
                    "terminatedAtUtc cannot be later than the existing termination time",
                    PermitRejectReason.INVALID_PERMIT_RANGE);
        }
        permitRepository.terminate(permitId, terminatedAt);
        SamplingPermit updated = permitRepository.findById(permitId).orElseThrow();
        return completePermit(request.requestId(), HttpStatus.OK, updated);
    }

    /**
     * 撤销许可：置 REVOKED，并把该许可下尚未合并（RECEIVED）的观测逐条标记 PERMIT_REVIEW、写入复核快照。
     * 已 MERGED 的观测与已人工裁决（RESOLVED）的簇不重算、不回退。
     */
    @Transactional
    public RevokeOutcome revokePermit(String permitId, RevokePermitRequest request) {
        String fingerprint = fingerprint("PERMIT_REVOKE", permitId, request.reason());
        RevokeOutcome replayed = checkReplay(request.requestId(), fingerprint, RevokeResult.class);
        if (replayed != null) {
            return replayed;
        }
        RevokeOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "PERMIT_REVOKE",
                RevokeResult.class);
        if (concurrent != null) {
            return concurrent;
        }

        SamplingPermit permit = lockPermit(permitId);
        if (permit.status() != PermitStatus.ACTIVE) {
            throw ApiException.unprocessable("permit is not active: " + permitId,
                    PermitRejectReason.PERMIT_NOT_ACTIVE);
        }
        Instant revokedAt = Instant.now(clock);
        permitRepository.revoke(permitId, revokedAt);

        List<ObservationSnapshot> received =
                observationRepository.findFullByPermitAndStatusForUpdate(permitId, ObservationStatus.RECEIVED);
        List<String> reviewedObservationIds = new ArrayList<>();
        for (ObservationSnapshot obs : received) {
            ReviewSnapshot snapshot = new ReviewSnapshot(
                    permitId, obs.permitVersion(), revokedAt, request.reason(),
                    obs.deviceId(), obs.gridId(), obs.observedAtUtc(), ObservationStatus.RECEIVED);
            observationRepository.markSinglePermitReview(obs.observationId(), writeJson(snapshot));
            reviewedObservationIds.add(obs.observationId());
        }
        SamplingPermit updated = permitRepository.findById(permitId).orElseThrow();
        RevokeResult result = new RevokeResult(PermitResponse.of(updated),
                revokedAt, request.reason(), List.copyOf(reviewedObservationIds));
        return completeRevoke(request.requestId(), HttpStatus.OK, result);
    }

    /**
     * 查询许可（含计数与剩余配额）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public PermitResponse getPermit(String permitId) {
        SamplingPermit permit = permitRepository.findById(permitId)
                .orElseThrow(() -> ApiException.notFound("permit not found: " + permitId));
        return PermitResponse.of(permit);
    }

    // ---------- 采样提交 ----------

    /**
     * 采样提交：设备/网格/时刻唯一命中一张 ACTIVE 许可，扣增 1 个计数（不得超限），写入 RECEIVED 观测。
     * 无许可/命中不唯一/超限分别以可区分原因码返回 422。同 requestId 同参重放原结果，失败不占键。
     */
    @Transactional
    public SampleOutcome submitSample(SampleSubmitRequest request) {
        // requestId 幂等指纹只依赖请求参数，保证许可事后撤销/结束时仍可重放原成功结果
        String fingerprint = fingerprint("SAMPLE_SUBMIT", request.observationId(), request.deviceId(),
                String.valueOf(request.gridId()), request.observedAtUtc().toString(),
                request.location(), request.reading(), request.note());
        SampleOutcome replayed = checkReplay(request.requestId(), fingerprint, SampleResult.class);
        if (replayed != null) {
            return replayed;
        }
        SampleOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint, "SAMPLE_SUBMIT",
                SampleResult.class);
        if (concurrent != null) {
            return concurrent;
        }

        permitRepository.lockDevice(request.deviceId());
        List<SamplingPermit> devicePermits =
                permitRepository.findByDeviceForUpdate(request.deviceId());

        // permitKey 含命中许可版本：先按该设备各许可版本计算候选指纹做同键重放（即使原许可事后撤销也重放原结果）。
        // 该判定先于“观测已存在”检查，保证不同 requestId 的同键重放不被误判为重复创建。
        for (SamplingPermit candidatePermit : devicePermits) {
            String candidateKey = permitKey(candidatePermit.permitVersion(), request);
            Optional<PermitSampleKeyRepository.SampleKey> existingKey =
                    sampleKeyRepository.find(candidateKey);
            if (existingKey.isPresent()) {
                return replaySampleByKey(request.requestId(), existingKey.get());
            }
        }

        if (observationRepository.findCurrent(request.observationId()).isPresent()) {
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }

        SamplingPermit permit = resolveUniquePermit(
                devicePermits, request.deviceId(), request.gridId(), request.observedAtUtc());
        consumeCapacity(permit, 1, request.observationId());

        ObservationSnapshot snapshot = new ObservationSnapshot(
                request.observationId(), 1, request.location(), request.reading(), request.note(), false,
                request.deviceId(), request.gridId(), request.observedAtUtc(),
                permit.permitId(), permit.permitVersion(), ObservationStatus.RECEIVED, null);
        String permitKey = permitKey(permit.permitVersion(), request);
        try {
            observationRepository.insertSampledCurrent(snapshot);
            observationRepository.insertVersion(snapshot);
            // 与观测写入、计数扣减同事务登记指纹；并发同键由主键串行化后走重放
            sampleKeyRepository.insert(new PermitSampleKeyRepository.SampleKey(
                    permitKey, request.requestId(), request.observationId(), permit.permitId()));
        } catch (DuplicateKeyException e) {
            // 唯一键冲突可能来自 permitKey 或观测主键：指纹已存在则同键重放，否则按重复观测处理
            Optional<PermitSampleKeyRepository.SampleKey> raced = sampleKeyRepository.find(permitKey);
            if (raced.isPresent()) {
                return replaySampleByKey(request.requestId(), raced.get());
            }
            throw ApiException.conflict("observation already exists: " + request.observationId(), null);
        }
        SamplingPermit updated = permitRepository.findById(permit.permitId()).orElseThrow();
        SampleResult result = new SampleResult(
                ObservationResponse.ofFull(snapshot, null), permitKey, PermitResponse.of(updated));
        return completeSample(request.requestId(), HttpStatus.CREATED, result);
    }

    /**
     * 计算采样许可指纹 permitKey：设备 + 命中许可版本 + 网格 + 采集时刻 + 观测内容（含观测标识）。
     */
    private String permitKey(String permitVersion, SampleSubmitRequest request) {
        return fingerprint("PERMIT_KEY", request.deviceId(), permitVersion,
                String.valueOf(request.gridId()), request.observedAtUtc().toString(),
                request.location(), request.reading(), request.note(), request.observationId());
    }

    /**
     * 依据已落库的 permitKey 重放首次成功接收结果：不重复扣减计数；
     * 若本次 requestId 与首次不同，则把原响应也回填到本次 requestId 的占位记录。
     */
    private SampleOutcome replaySampleByKey(String currentRequestId,
                                            PermitSampleKeyRepository.SampleKey key) {
        RequestLogEntry original = requestLogRepository.find(key.requestId())
                .orElseThrow(() -> new IllegalStateException(
                        "original request log missing for permitKey: " + key.permitKey()));
        SampleResult body = readJson(original.responseBody(), SampleResult.class);
        if (!currentRequestId.equals(key.requestId())) {
            requestLogRepository.complete(currentRequestId, original.responseStatus(),
                    original.responseBody());
        }
        return new SampleOutcome(original.responseStatus(), body);
    }

    // ---------- 批量离线合并 ----------

    /**
     * 批量离线合并：先按完整最终计数、坐标边界与冲突簇整体预校验，再在单事务内整批应用。
     * 任一项无许可/命中不唯一/超限返回 422，全部观测、版本、计数与簇变化回滚。
     */
    @Transactional
    public BatchOutcome batchMerge(BatchMergeRequest request) {
        String batchFingerprint = fingerprint("BATCH_MERGE", request.requestId(),
                String.valueOf(request.items().size()), itemsFingerprint(request.items()));
        BatchOutcome replayed = checkReplay(request.requestId(), batchFingerprint, BatchMergeResponse.class);
        if (replayed != null) {
            return replayed;
        }
        BatchOutcome concurrent = insertPlaceholder(request.requestId(), batchFingerprint, "BATCH_MERGE",
                BatchMergeResponse.class);
        if (concurrent != null) {
            return concurrent;
        }

        // 1) 观测行存在性/基线与既有 PENDING 簇预检，同时收集涉及设备（去重，稳定顺序）。
        List<ItemContext> contexts = new ArrayList<>();
        List<String> deviceOrder = new ArrayList<>();
        for (BatchMergeRequest.Item item : request.items()) {
            ObservationSnapshot current = observationRepository.findCurrentFull(item.observationId()).orElse(null);
            boolean creating = item.baseVersion() == null || item.baseVersion() <= 0;
            if (creating) {
                if (current != null) {
                    throw ApiException.conflict("observation already exists: " + item.observationId(), null);
                }
            } else {
                if (current == null) {
                    throw ApiException.notFound("observation not found: " + item.observationId());
                }
                if (current.deleted()) {
                    throw ApiException.gone("observation already deleted: " + item.observationId());
                }
                observationRepository.findVersion(item.observationId(), item.baseVersion())
                        .orElseThrow(() -> ApiException.notFound(
                                "base version not found: " + item.observationId() + "@" + item.baseVersion()));
                for (MergeCluster cluster : clusterRepository.findByObservation(item.observationId())) {
                    if (cluster.pending()) {
                        throw ApiException.unprocessable(
                                "observation has a pending conflict cluster: " + item.observationId(),
                                PermitRejectReason.CLUSTER_PENDING);
                    }
                }
            }
            contexts.add(new ItemContext(item, current, creating, null));
            if (!deviceOrder.contains(item.deviceId())) {
                deviceOrder.add(item.deviceId());
            }
        }

        // 2) 按设备ID排序后依次加锁，保证多设备批量的全局一致加锁顺序，避免并发批死锁；
        //    加载并缓存各设备许可行（含已撤销行，命中只取 ACTIVE）。
        Map<String, List<SamplingPermit>> permitsByDevice = new LinkedHashMap<>();
        for (String deviceId : deviceOrder.stream().sorted().toList()) {
            permitRepository.lockDevice(deviceId);
            permitsByDevice.put(deviceId, permitRepository.findByDeviceForUpdate(deviceId));
        }

        // 3) 逐行锁观测、唯一命中许可，并重算许可三元组一致性。
        List<ItemContext> locked = new ArrayList<>();
        for (ItemContext ctx : contexts) {
            ObservationSnapshot row = ctx.creating
                    ? null
                    : observationRepository.findCurrentFullForUpdate(ctx.item.observationId()).orElseThrow();
            SamplingPermit permit = resolveUniquePermit(
                    permitsByDevice.get(ctx.item.deviceId()),
                    ctx.item.deviceId(), ctx.item.gridId(), ctx.item.observedAtUtc());
            if (!ctx.creating) {
                if (!permit.permitId().equals(row.permitId())) {
                    throw ApiException.unprocessable(
                            "permit mismatch for observation '" + ctx.item.observationId()
                                    + "': item permit does not match the received observation's permit",
                            PermitRejectReason.PERMIT_MISMATCH);
                }
                Integer rowGrid = row.gridId();
                if (!ctx.item.deviceId().equals(row.deviceId())
                        || rowGrid == null || ctx.item.gridId().intValue() != rowGrid
                        || !ctx.item.observedAtUtc().equals(row.observedAtUtc())) {
                    throw ApiException.unprocessable(
                            "permit sampling coordinates mismatch for observation '"
                                    + ctx.item.observationId() + "' (device/grid/observedAt)",
                            PermitRejectReason.PERMIT_MISMATCH);
                }
            }
            locked.add(new ItemContext(ctx.item, row, ctx.creating, permit));
        }

        // 4) 坐标边界与完整最终计数预校验。
        //    坐标边界：同一 (设备,命中许可) 下本批网格最小/最大都须落在该许可闭区间；
        //    计数 = 许可库内现有计数 + 本批新观测（创建）数；已存在观测的合并不重复计数。
        record PermitKey(String deviceId, String permitId) {
        }
        Map<PermitKey, int[]> gridBoundsByPermit = new LinkedHashMap<>();
        Map<String, Integer> createCountByPermit = new LinkedHashMap<>();
        for (ItemContext ctx : locked) {
            PermitKey key = new PermitKey(ctx.item.deviceId(), ctx.permit.permitId());
            int[] bounds = gridBoundsByPermit.computeIfAbsent(
                    key, k -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
            bounds[0] = Math.min(bounds[0], ctx.item.gridId());
            bounds[1] = Math.max(bounds[1], ctx.item.gridId());
            if (ctx.creating) {
                createCountByPermit.merge(ctx.permit.permitId(), 1, Integer::sum);
            }
        }
        for (Map.Entry<PermitKey, int[]> entry : gridBoundsByPermit.entrySet()) {
            int[] bounds = entry.getValue();
            SamplingPermit permit = findPermit(locked, entry.getKey().permitId());
            if (!permit.coversGridRange(bounds[0], bounds[1])) {
                throw ApiException.unprocessable(
                        "batch grid boundary [" + bounds[0] + ", " + bounds[1]
                                + "] exceeds permit '" + permit.permitId() + "' range for device '"
                                + entry.getKey().deviceId() + "'",
                        PermitRejectReason.NO_PERMIT);
            }
        }
        for (Map.Entry<String, Integer> entry : createCountByPermit.entrySet()) {
            SamplingPermit permit = findPermit(locked, entry.getKey());
            long finalCount = (long) permit.observedCount() + entry.getValue();
            if (finalCount > permit.maxObservations()) {
                throw ApiException.unprocessable(
                        "permit '" + permit.permitId() + "' final observed count " + finalCount
                                + " would exceed max " + permit.maxObservations(),
                        PermitRejectReason.LIMIT_EXCEEDED);
            }
        }

        // 5) 预校验全部通过：原子应用整批（创建+扣计数 / 三方合并 / 登记冲突簇）。
        List<BatchMergeResponse.ItemResult> itemResults = new ArrayList<>();
        Map<String, MergeCluster> clusters = new LinkedHashMap<>();
        Map<String, Integer> grantedByPermit = new LinkedHashMap<>();
        for (ItemContext ctx : locked) {
            itemResults.add(applyBatchItem(request.requestId(), ctx, clusters, grantedByPermit));
        }
        // 创建项的计数在所有判定通过后一次性条件扣增，作为超限的最终数据库防线
        for (Map.Entry<String, Integer> entry : grantedByPermit.entrySet()) {
            SamplingPermit permit = findPermit(locked, entry.getKey());
            int rows = permitRepository.incrementCountIfWithinLimit(
                    entry.getKey(), entry.getValue(), permit.maxObservations());
            if (rows != 1) {
                throw ApiException.unprocessable(
                        "permit capacity changed concurrently: " + entry.getKey(),
                        PermitRejectReason.LIMIT_EXCEEDED);
            }
        }

        List<BatchMergeResponse.ClusterView> clusterViews = clusters.values().stream()
                .map(c -> new BatchMergeResponse.ClusterView(
                        c.clusterId(), c.observationId(), List.copyOf(c.conflictFields()), c.status()))
                .toList();
        BatchMergeResponse response =
                new BatchMergeResponse(request.requestId(), List.copyOf(itemResults), clusterViews);
        return completeBatch(request.requestId(), HttpStatus.OK, response);
    }

    // ---------- 冲突簇查询与裁决 ----------

    /**
     * 按簇标识查询冲突簇；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ClusterResponse getCluster(String clusterId) {
        MergeCluster cluster = clusterRepository.findById(clusterId)
                .orElseThrow(() -> ApiException.notFound("cluster not found: " + clusterId));
        return ClusterResponse.of(cluster);
    }

    /**
     * 按批量请求标识查询本批冲突簇。
     */
    @Transactional(readOnly = true)
    public List<ClusterResponse> listClustersByBatch(String batchRequestId) {
        return clusterRepository.findByBatch(batchRequestId).stream().map(ClusterResponse::of).toList();
    }

    /**
     * 冲突簇人工裁决：PENDING 簇以入簇候选快照与入簇基线重放原始三方冲突，人工选择必须恰好覆盖
     * 重算出的冲突字段；原子写入观测新版本（或无变化）、不可变 conflict_resolution 与簇 RESOLVED。
     * 已 RESOLVED 的簇不重算；requestId/resolutionId 同参重放，改参 409。
     */
    @Transactional
    public ResolveClusterOutcome resolveCluster(String clusterId, ResolveConflictRequest request) {
        MergeCluster cluster = clusterRepository.findById(clusterId)
                .orElseThrow(() -> ApiException.notFound("cluster not found: " + clusterId));
        if (MergeCluster.RESOLVED.equals(cluster.status())) {
            return alreadyResolvedOutcome(cluster);
        }

        // 先以簇内候选等不可变上下文做幂等指纹，保证裁决重放不依赖许可当前状态
        Map<String, FieldSelection> selections = parseSelections(request.selections());
        String normalizedSelections = writeSelectionsJson(selections);
        String fingerprint = fingerprint("CLUSTER_RESOLVE", clusterId, request.resolutionId(),
                String.valueOf(cluster.baseVersion()),
                cluster.candidateLocation(), cluster.candidateReading(), cluster.candidateNote(),
                request.location(), request.reading(), request.note(),
                String.valueOf(request.baseVersion()), String.valueOf(request.expectedCurrentVersion()),
                normalizedSelections, request.operator());
        ResolveClusterOutcome replayed = checkReplay(request.requestId(), fingerprint,
                ResolveClusterResult.class);
        if (replayed != null) {
            return replayed;
        }
        ResolveClusterOutcome concurrent = insertPlaceholder(request.requestId(), fingerprint,
                "CLUSTER_RESOLVE", ResolveClusterResult.class);
        if (concurrent != null) {
            return concurrent;
        }

        ObservationSnapshot current =
                observationRepository.findCurrentFullForUpdate(cluster.observationId()).orElseThrow(() ->
                        ApiException.notFound("observation not found: " + cluster.observationId()));
        if (current.deleted()) {
            throw ApiException.gone("observation already deleted: " + cluster.observationId());
        }
        // 裁决请求提交的候选必须与登记入簇的候选完整一致，防止对同一簇提交另一套候选
        if (!Objects.equals(request.location(), cluster.candidateLocation())
                || !MergeRules.readingEquals(request.reading(), cluster.candidateReading())
                || !Objects.equals(request.note(), cluster.candidateNote())) {
            throw ApiException.badRequest(
                    "candidate values must match the registered cluster candidate");
        }
        if (request.baseVersion() != cluster.baseVersion()) {
            throw ApiException.conflict("baseVersion does not match cluster base", current.version());
        }
        if (request.expectedCurrentVersion() != current.version()) {
            throw ApiException.conflict("expectedCurrentVersion mismatch", current.version());
        }
        ObservationSnapshot base = observationRepository.findVersion(
                        cluster.observationId(), cluster.baseVersion())
                .orElseThrow(() -> ApiException.notFound(
                        "base version not found: " + cluster.observationId() + "@" + cluster.baseVersion()));

        List<String> conflictFields = new ArrayList<>();
        boolean locationConflict = MergeRules.fieldConflicts(
                base.location(), current.location(), cluster.candidateLocation(), false);
        if (locationConflict) {
            conflictFields.add("location");
        }
        boolean readingConflict = MergeRules.fieldConflicts(
                base.reading(), current.reading(), cluster.candidateReading(), true);
        if (readingConflict) {
            conflictFields.add("reading");
        }
        boolean noteConflict = MergeRules.fieldConflicts(
                base.note(), current.note(), cluster.candidateNote(), false);
        if (noteConflict) {
            conflictFields.add("note");
        }
        validateSelectionCoverage(selections.keySet(), conflictFields);

        String resolvedLocation = resolveFieldValue(locationConflict, selections.get("location"),
                base.location(), current.location(), cluster.candidateLocation(), false);
        String resolvedReading = resolveFieldValue(readingConflict, selections.get("reading"),
                base.reading(), current.reading(), cluster.candidateReading(), true);
        String resolvedNote = resolveFieldValue(noteConflict, selections.get("note"),
                base.note(), current.note(), cluster.candidateNote(), false);

        ObservationSnapshot merged = new ObservationSnapshot(cluster.observationId(), current.version(),
                resolvedLocation, resolvedReading, resolvedNote, false);
        boolean contentChanged = !sameContent(merged, current);
        int newVersion = contentChanged ? current.version() + 1 : current.version();
        if (contentChanged) {
            ObservationSnapshot next = new ObservationSnapshot(cluster.observationId(), newVersion,
                    resolvedLocation, resolvedReading, resolvedNote, false,
                    current.deviceId(), current.gridId(), current.observedAtUtc(),
                    current.permitId(), current.permitVersion(),
                    ObservationStatus.MERGED, current.reviewSnapshotJson());
            observationRepository.updateSampledCurrent(next);
            observationRepository.insertVersion(next);
        } else {
            observationRepository.markMerged(cluster.observationId());
        }

        ResolutionRecord record = new ResolutionRecord(
                request.resolutionId(), cluster.observationId(), request.requestId(),
                cluster.baseVersion(), current.version(), newVersion,
                cluster.candidateLocation(), cluster.candidateReading(), cluster.candidateNote(),
                List.copyOf(conflictFields), normalizedSelections, request.operator(),
                Instant.now(clock), contentChanged);
        try {
            resolutionRepository.insert(record);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(
                    "resolutionId reused with different parameters: " + request.resolutionId(),
                    current.version());
        }
        int marked = clusterRepository.markResolved(clusterId, request.resolutionId(), Instant.now(clock));
        if (marked != 1) {
            throw ApiException.conflict("cluster already resolved: " + clusterId, current.version());
        }

        ObservationSnapshot pointed = observationRepository.findVersion(
                cluster.observationId(), newVersion).orElseThrow();
        MergeCluster updated = clusterRepository.findById(clusterId).orElseThrow();
        ResolveClusterResult result = new ResolveClusterResult(
                ResolutionResponse.of(record, pointed, objectMapper), ClusterResponse.of(updated));
        return completeClusterResolve(request.requestId(), HttpStatus.OK, result);
    }

    /**
     * 查询观测当前完整状态（含许可版本、业务状态、复核标记与复核原因）。
     */
    @Transactional(readOnly = true)
    public ObservationResponse getObservation(String observationId) {
        ObservationSnapshot snapshot = observationRepository.findCurrentFull(observationId)
                .orElseThrow(() -> ApiException.notFound("observation not found: " + observationId));
        ReviewSnapshot review = snapshot.reviewSnapshotJson() == null
                ? null
                : readJson(snapshot.reviewSnapshotJson(), ReviewSnapshot.class);
        return ObservationResponse.ofFull(snapshot, review);
    }

    // ---------- 内部辅助 ----------

    private ResolveClusterOutcome alreadyResolvedOutcome(MergeCluster cluster) {
        ResolutionRecord record = resolutionRepository.findByResolutionId(cluster.resolutionId())
                .orElseThrow(() -> new IllegalStateException(
                        "resolved cluster missing resolution: " + cluster.resolutionId()));
        ObservationSnapshot pointed = observationRepository.findVersion(
                        record.observationId(), record.newVersion())
                .orElseThrow(() -> new IllegalStateException("pointed version missing"));
        return new ResolveClusterOutcome(HttpStatus.OK.value(),
                new ResolveClusterResult(
                        ResolutionResponse.of(record, pointed, objectMapper), ClusterResponse.of(cluster)));
    }

    /**
     * 锁定许可所在设备与许可行，并返回许可；许可不存在抛 404。
     */
    private SamplingPermit lockPermit(String permitId) {
        SamplingPermit looked = permitRepository.findById(permitId)
                .orElseThrow(() -> ApiException.notFound("permit not found: " + permitId));
        permitRepository.lockDevice(looked.deviceId());
        return permitRepository.findByIdForUpdate(permitId).orElseThrow();
    }

    /**
     * 解析唯一命中许可；无许可 422(NO_PERMIT)、命中多于一个 422(NOT_UNIQUE)。调用方须持有设备锁。
     */
    private SamplingPermit resolveUniquePermit(List<SamplingPermit> devicePermits,
                                               String deviceId, int gridId, Instant observedAtUtc) {
        List<SamplingPermit> matches = devicePermits.stream()
                .filter(p -> PermitRules.matches(p, deviceId, gridId, observedAtUtc))
                .toList();
        if (matches.isEmpty()) {
            throw ApiException.unprocessable(
                    "no active permit matches device='" + deviceId + "', grid=" + gridId
                            + ", observedAtUtc=" + observedAtUtc,
                    PermitRejectReason.NO_PERMIT);
        }
        if (matches.size() > 1) {
            throw ApiException.unprocessable(
                    "permit match not unique for device='" + deviceId + "', grid=" + gridId
                            + ", observedAtUtc=" + observedAtUtc + ": "
                            + matches.stream().map(SamplingPermit::permitId).sorted().toList(),
                    PermitRejectReason.NOT_UNIQUE);
        }
        return matches.get(0);
    }

    /**
     * 容量扣减（锁内、上限判断 + 条件 UPDATE 双保险，防止并发超发）。
     */
    private void consumeCapacity(SamplingPermit permit, int delta, String observationId) {
        if ((long) permit.observedCount() + delta > permit.maxObservations()) {
            throw ApiException.unprocessable(
                    "permit '" + permit.permitId() + "' capacity exhausted for observation "
                            + observationId,
                    PermitRejectReason.LIMIT_EXCEEDED);
        }
        int rows = permitRepository.incrementCountIfWithinLimit(permit.permitId(), delta,
                permit.maxObservations());
        if (rows != 1) {
            throw ApiException.unprocessable(
                    "permit '" + permit.permitId() + "' capacity exhausted for observation "
                            + observationId,
                    PermitRejectReason.LIMIT_EXCEEDED);
        }
    }

    private SamplingPermit findPermit(List<ItemContext> contexts, String permitId) {
        return contexts.stream().map(ItemContext::permit).filter(p -> p.permitId().equals(permitId))
                .findFirst().orElseThrow(() -> new IllegalStateException("permit not in batch: " + permitId));
    }

    /**
     * 应用单个批量项：创建（RECEIVED+计数）或三方合并（MERGED / 冲突登记 PENDING 簇，内容不变）。
     */
    private BatchMergeResponse.ItemResult applyBatchItem(
            String batchRequestId, ItemContext ctx,
            Map<String, MergeCluster> clusters,
            Map<String, Integer> grantedByPermit) {
        BatchMergeRequest.Item item = ctx.item;
        if (ctx.creating) {
            ObservationSnapshot snapshot = new ObservationSnapshot(
                    item.observationId(), 1, item.location(), item.reading(), item.note(), false,
                    item.deviceId(), item.gridId(), item.observedAtUtc(),
                    ctx.permit.permitId(), ctx.permit.permitVersion(),
                    ObservationStatus.RECEIVED, null);
            try {
                observationRepository.insertSampledCurrent(snapshot);
                observationRepository.insertVersion(snapshot);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("observation already exists: " + item.observationId(), null);
            }
            grantedByPermit.merge(ctx.permit.permitId(), 1, Integer::sum);
            return new BatchMergeResponse.ItemResult(
                    item.observationId(), true, 1, ObservationStatus.RECEIVED.name(),
                    false, null, ctx.permit.permitId(), ctx.permit.permitVersion());
        }

        ObservationSnapshot current = ctx.current;
        ObservationSnapshot base = observationRepository.findVersion(item.observationId(),
                item.baseVersion()).orElseThrow();
        List<String> conflictFields = new ArrayList<>();
        String mergedLocation = MergeRules.mergeField(
                base.location(), current.location(), item.location(), false, "location", conflictFields);
        String mergedReading = MergeRules.mergeField(
                base.reading(), current.reading(), item.reading(), true, "reading", conflictFields);
        String mergedNote = MergeRules.mergeField(
                base.note(), current.note(), item.note(), false, "note", conflictFields);

        if (!conflictFields.isEmpty()) {
            String clusterId = batchRequestId + ":" + item.observationId();
            MergeCluster cluster = new MergeCluster(
                    clusterId, batchRequestId, item.observationId(), item.baseVersion(),
                    item.location(), item.reading(), item.note(),
                    List.copyOf(conflictFields), MergeCluster.PENDING, null, null);
            clusterRepository.insertPending(cluster);
            clusters.put(item.observationId(), cluster);
            // 冲突：观测内容、版本与业务状态保持不变，等待人工裁决
            return new BatchMergeResponse.ItemResult(
                    item.observationId(), false, current.version(),
                    current.observationStatus() == null ? null : current.observationStatus().name(),
                    true, clusterId, current.permitId(), current.permitVersion());
        }

        ObservationSnapshot merged = new ObservationSnapshot(item.observationId(), current.version(),
                mergedLocation, mergedReading, mergedNote, false);
        if (!sameContent(merged, current)) {
            ObservationSnapshot next = new ObservationSnapshot(item.observationId(), current.version() + 1,
                    mergedLocation, mergedReading, mergedNote, false,
                    current.deviceId(), current.gridId(), current.observedAtUtc(),
                    current.permitId(), current.permitVersion(), ObservationStatus.MERGED,
                    current.reviewSnapshotJson());
            observationRepository.updateSampledCurrent(next);
            observationRepository.insertVersion(next);
            return new BatchMergeResponse.ItemResult(
                    item.observationId(), false, next.version(), ObservationStatus.MERGED.name(),
                    false, null, current.permitId(), current.permitVersion());
        }
        observationRepository.markMerged(item.observationId());
        return new BatchMergeResponse.ItemResult(
                item.observationId(), false, current.version(), ObservationStatus.MERGED.name(),
                false, null, current.permitId(), current.permitVersion());
    }

    private boolean sameContent(ObservationSnapshot merged, ObservationSnapshot current) {
        return Objects.equals(merged.location(), current.location())
                && Objects.equals(merged.note(), current.note())
                && MergeRules.readingEquals(merged.reading(), current.reading());
    }

    private Map<String, FieldSelection> parseSelections(Map<String, String> raw) {
        Map<String, FieldSelection> selections = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            String field = entry.getKey() == null ? null : entry.getKey().trim();
            if (field == null || !FIELDS.contains(field)) {
                throw ApiException.badRequest("selection key must be one of location/reading/note: " + field);
            }
            FieldSelection selection = FieldSelection.fromValue(entry.getValue());
            if (selection == null) {
                throw ApiException.badRequest(
                        "selection for field '" + field + "' must be CURRENT or CANDIDATE");
            }
            if (selections.put(field, selection) != null) {
                throw ApiException.badRequest("duplicate selection for field: " + field);
            }
        }
        return selections;
    }

    private void validateSelectionCoverage(Set<String> selectedFields, List<String> conflictFields) {
        Set<String> conflictSet = Set.copyOf(conflictFields);
        if (!selectedFields.equals(conflictSet)) {
            throw ApiException.badRequest(
                    "selections must cover exactly the recalculated conflict fields: "
                            + conflictFields + ", got: " + selectedFields);
        }
    }

    private String resolveFieldValue(boolean conflict, FieldSelection selection,
                                     String base, String current, String candidate, boolean numeric) {
        if (conflict) {
            return selection == FieldSelection.CANDIDATE ? candidate : current;
        }
        return MergeRules.valueEquals(candidate, base, numeric) ? current : candidate;
    }

    private String writeSelectionsJson(Map<String, FieldSelection> selections) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String field : FIELDS) {
            FieldSelection selection = selections.get(field);
            if (selection != null) {
                ordered.put(field, selection.name());
            }
        }
        return writeJson(ordered);
    }

    private String itemsFingerprint(List<BatchMergeRequest.Item> items) {
        StringBuilder raw = new StringBuilder();
        for (BatchMergeRequest.Item item : items) {
            raw.append(item.observationId()).append(SEPARATOR)
                    .append(item.baseVersion() == null ? "0" : item.baseVersion()).append(SEPARATOR)
                    .append(item.deviceId()).append(SEPARATOR)
                    .append(item.gridId()).append(SEPARATOR)
                    .append(item.observedAtUtc()).append(SEPARATOR)
                    .append(item.location()).append(SEPARATOR)
                    .append(item.reading()).append(SEPARATOR)
                    .append(item.note()).append(SEPARATOR).append('');
        }
        return sha256(raw.toString());
    }

    // ---------- 幂等去重（request_log 占位 + 回填，失败回滚不占键） ----------

    @SuppressWarnings("unchecked")
    private <O> O checkReplay(String requestId, String fingerprint, Class<?> bodyType) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        if (!entry.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return (O) replayOutcome(bodyType, entry.responseStatus(),
                readJson(entry.responseBody(), bodyType));
    }

    @SuppressWarnings("unchecked")
    private <O> O insertPlaceholder(String requestId, String fingerprint, String operation,
                                    Class<?> bodyType) {
        try {
            requestLogRepository.insertPlaceholder(requestId, fingerprint, operation);
            return null;
        } catch (DuplicateKeyException e) {
            RequestLogEntry entry = requestLogRepository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("requestId conflict: " + requestId, null));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict(
                        "requestId reused with different parameters: " + requestId, null);
            }
            return (O) replayOutcome(bodyType, entry.responseStatus(),
                    readJson(entry.responseBody(), bodyType));
        }
    }

    private Object replayOutcome(Class<?> bodyType, int status, Object body) {
        if (bodyType == PermitResponse.class) {
            return new PermitOutcome(status, (PermitResponse) body);
        }
        if (bodyType == RevokeResult.class) {
            return new RevokeOutcome(status, (RevokeResult) body);
        }
        if (bodyType == SampleResult.class) {
            return new SampleOutcome(status, (SampleResult) body);
        }
        if (bodyType == BatchMergeResponse.class) {
            return new BatchOutcome(status, (BatchMergeResponse) body);
        }
        if (bodyType == ResolveClusterResult.class) {
            return new ResolveClusterOutcome(status, (ResolveClusterResult) body);
        }
        throw new IllegalStateException("unsupported replay type: " + bodyType);
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize result", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored result", e);
        }
    }

    private PermitOutcome completePermit(String requestId, HttpStatus status, SamplingPermit permit) {
        PermitResponse body = PermitResponse.of(permit);
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new PermitOutcome(status.value(), body);
    }

    private RevokeOutcome completeRevoke(String requestId, HttpStatus status, RevokeResult result) {
        requestLogRepository.complete(requestId, status.value(), writeJson(result));
        return new RevokeOutcome(status.value(), result);
    }

    private SampleOutcome completeSample(String requestId, HttpStatus status, SampleResult result) {
        requestLogRepository.complete(requestId, status.value(), writeJson(result));
        return new SampleOutcome(status.value(), result);
    }

    private BatchOutcome completeBatch(String requestId, HttpStatus status, BatchMergeResponse response) {
        requestLogRepository.complete(requestId, status.value(), writeJson(response));
        return new BatchOutcome(status.value(), response);
    }

    private ResolveClusterOutcome completeClusterResolve(String requestId, HttpStatus status,
                                                         ResolveClusterResult result) {
        requestLogRepository.complete(requestId, status.value(), writeJson(result));
        return new ResolveClusterOutcome(status.value(), result);
    }

    /**
     * 批量项处理上下文：请求项、加锁前读到的当前行（创建时为 null）、是否新建与解析出的命中许可。
     */
    private record ItemContext(
            BatchMergeRequest.Item item,
            ObservationSnapshot current,
            boolean creating,
            SamplingPermit permit) {
    }

    public record PermitOutcome(int status, PermitResponse body) {
    }

    public record RevokeResult(PermitResponse permit, Instant revokedAtUtc, String reason,
                               List<String> reviewedObservationIds) {
    }

    public record RevokeOutcome(int status, RevokeResult body) {
    }

    public record SampleResult(ObservationResponse observation, String permitKey, PermitResponse permit) {
    }

    public record SampleOutcome(int status, SampleResult body) {
    }

    public record BatchOutcome(int status, BatchMergeResponse body) {
    }

    public record ResolveClusterResult(ResolutionResponse resolution, ClusterResponse cluster) {
    }

    public record ResolveClusterOutcome(int status, ResolveClusterResult body) {
    }
}
