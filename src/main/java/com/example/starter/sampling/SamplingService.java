package com.example.starter.sampling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 观测采样许可业务服务。
 *
 * <p>许可创建/提前结束/撤销、观测提交、批量合并与簇裁决五类写操作均在全局公平顺序锁内执行，
 * 锁覆盖整个事务（含提交），保证按提交顺序逐个裁决；许可计数扣减另以 SELECT ... FOR UPDATE 行锁保护。
 *
 * <p>提交观测：设备、采集网格与采集时刻必须命中唯一有效许可（ACTIVE 或提前结束的 ENDED），
 * 许可版本须一致，且只扣减该许可计数、不得超过上限；任一失败返回 422 并追加可查询的拒绝记录，
 * 失败不占用 permitKey。permitKey 为设备+许可版本+网格+时刻+观测内容的 SHA-256 指纹，同键重放。
 *
 * <p>批量离线合并：先按坐标边界、许可命中唯一性、完整最终计数预校验，任一失败 422，
 * 全部观测与簇变化随事务回滚、批次键不占用；预校验通过后同设备同网格单元内容不一致的
 * 新观测形成待裁决冲突簇，其余直接合并。
 *
 * <p>许可提前结束只前移有效期终点，不删除已接收观测；撤销许可将未合并观测标记 PERMIT_REVIEW
 * 并逐条写入状态快照，已人工裁决簇的成员已合并、不重算。新许可不回溯改写历史拒绝记录。
 */
@Service
public class SamplingService {

    private static final String SEPARATOR = "";

    /**
     * 采样网格全局坐标上限：坐标必须落在 [0, MAX_GRID_COORDINATE] 内，否则按坐标越界拒绝。
     */
    static final int MAX_GRID_COORDINATE = 999_999;

    /**
     * 全局顺序锁（公平）：五类写操作按提交顺序逐个裁决，锁覆盖整个事务（含提交）。
     * 状态仅在当前 JVM 生命周期内有效。
     */
    private final ReentrantLock orderLock = new ReentrantLock(true);

    private final PermitRepository permitRepository;
    private final SamplingObservationRepository observationRepository;
    private final RejectionRepository rejectionRepository;
    private final MergeBatchRepository mergeBatchRepository;
    private final ClusterRepository clusterRepository;
    private final SnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ObjectProvider<SamplingService> selfProvider;

    public SamplingService(PermitRepository permitRepository,
                           SamplingObservationRepository observationRepository,
                           RejectionRepository rejectionRepository,
                           MergeBatchRepository mergeBatchRepository,
                           ClusterRepository clusterRepository,
                           SnapshotRepository snapshotRepository,
                           ObjectMapper objectMapper,
                           Clock clock,
                           ObjectProvider<SamplingService> selfProvider) {
        this.permitRepository = permitRepository;
        this.observationRepository = observationRepository;
        this.rejectionRepository = rejectionRepository;
        this.mergeBatchRepository = mergeBatchRepository;
        this.clusterRepository = clusterRepository;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.selfProvider = selfProvider;
    }

    // ---------- 对外入口：全局顺序锁内执行事务 ----------

    /**
     * 创建采样许可：同设备有效许可在网格与时间的交集不能重叠，重叠返回 409。
     */
    public PermitResponse createPermit(CreatePermitRequest request) {
        orderLock.lock();
        try {
            return self().createPermitTx(request);
        } finally {
            orderLock.unlock();
        }
    }

    /**
     * 许可提前结束：有效期终点前移，只影响之后采样，不删除已接收观测。
     */
    public PermitResponse endPermit(String permitId, EndPermitRequest request) {
        orderLock.lock();
        try {
            return self().endPermitTx(permitId, request);
        } finally {
            orderLock.unlock();
        }
    }

    /**
     * 撤销许可：未合并观测标记 PERMIT_REVIEW 并逐条写入快照；已人工裁决簇不重算。
     */
    public PermitResponse revokePermit(String permitId) {
        orderLock.lock();
        try {
            return self().revokePermitTx(permitId);
        } finally {
            orderLock.unlock();
        }
    }

    /**
     * 提交观测：命中唯一有效许可并扣减其计数；失败记录拒绝原因且不占用 permitKey。
     */
    public SubmitOutcome submitObservation(SubmitObservationRequest request) {
        orderLock.lock();
        try {
            return self().submitObservationTx(request);
        } catch (SamplingException e) {
            self().recordRejections(e.rejections());
            throw e;
        } finally {
            orderLock.unlock();
        }
    }

    /**
     * 批量离线合并：预校验全部通过才写入，任一失败全部回滚并记录拒绝原因。
     */
    public BatchMergeOutcome mergeBatch(BatchMergeRequest request) {
        orderLock.lock();
        try {
            return self().mergeBatchTx(request);
        } catch (SamplingException e) {
            self().recordRejections(e.rejections());
            throw e;
        } finally {
            orderLock.unlock();
        }
    }

    /**
     * 冲突簇人工裁决：待裁决簇按指定内容裁决，成员观测统一置为已合并。
     */
    public ClusterResponse adjudicateCluster(String clusterId, AdjudicateClusterRequest request) {
        orderLock.lock();
        try {
            return self().adjudicateClusterTx(clusterId, request);
        } finally {
            orderLock.unlock();
        }
    }

    private SamplingService self() {
        return selfProvider.getObject();
    }

    // ---------- 许可写事务 ----------

    /**
     * 创建许可事务：校验网格与有效期合法性、标识唯一性与同设备有效许可不重叠。
     */
    @Transactional
    public PermitResponse createPermitTx(CreatePermitRequest request) {
        if (request.gridMinX() > request.gridMaxX() || request.gridMinY() > request.gridMaxY()) {
            throw SamplingException.badRequest("grid range invalid: min must be <= max");
        }
        if (!request.validFromUtc().isBefore(request.validToUtc())) {
            throw SamplingException.badRequest("validFromUtc must be before validToUtc");
        }
        if (permitRepository.findById(request.permitId()).isPresent()) {
            throw SamplingException.conflict(SamplingReasons.PERMIT_EXISTS,
                    "permit already exists: " + request.permitId());
        }
        List<SamplingPermit> overlaps = permitRepository.findOverlappingActive(
                request.deviceId(), request.gridMinX(), request.gridMaxX(),
                request.gridMinY(), request.gridMaxY(),
                request.validFromUtc(), request.validToUtc());
        if (!overlaps.isEmpty()) {
            throw SamplingException.conflict(SamplingReasons.PERMIT_OVERLAP,
                    "permit overlaps active permit in grid/time intersection: " + overlaps.get(0).permitId());
        }
        SamplingPermit permit = new SamplingPermit(request.permitId(), request.deviceId(),
                request.gridMinX(), request.gridMaxX(), request.gridMinY(), request.gridMaxY(),
                request.validFromUtc(), request.validToUtc(),
                request.maxObservations(), 0, request.permitVersion(), PermitStatus.ACTIVE);
        permitRepository.insert(permit);
        return PermitResponse.of(permit);
    }

    /**
     * 许可提前结束事务：新终点必须落在原 (validFrom, validTo) 区间内；已接收观测不受影响。
     */
    @Transactional
    public PermitResponse endPermitTx(String permitId, EndPermitRequest request) {
        SamplingPermit permit = permitRepository.findByIdForUpdate(permitId)
                .orElseThrow(() -> SamplingException.notFound("permit not found: " + permitId));
        if (permit.status() != PermitStatus.ACTIVE) {
            throw SamplingException.conflict(SamplingReasons.PERMIT_NOT_ACTIVE,
                    "permit is not active: " + permitId + " (" + permit.status() + ")");
        }
        if (!request.endedAtUtc().isAfter(permit.validFromUtc())
                || !request.endedAtUtc().isBefore(permit.validToUtc())) {
            throw SamplingException.badRequest(
                    "endedAtUtc must be within (validFromUtc, validToUtc): " + permitId);
        }
        permitRepository.updateWindowAndStatus(permitId, request.endedAtUtc(), PermitStatus.ENDED);
        return PermitResponse.of(permitRepository.findById(permitId).orElseThrow());
    }

    /**
     * 撤销许可事务：未合并（RECEIVED）观测逐条标记 PERMIT_REVIEW 并写入状态快照；
     * 已合并观测（含已人工裁决簇成员）不受影响、不重算。
     */
    @Transactional
    public PermitResponse revokePermitTx(String permitId) {
        SamplingPermit permit = permitRepository.findByIdForUpdate(permitId)
                .orElseThrow(() -> SamplingException.notFound("permit not found: " + permitId));
        if (permit.status() == PermitStatus.REVOKED) {
            throw SamplingException.conflict(SamplingReasons.PERMIT_NOT_ACTIVE,
                    "permit already revoked: " + permitId);
        }
        permitRepository.updateStatus(permitId, PermitStatus.REVOKED);
        List<String> receivedKeys = observationRepository.findReceivedKeysByPermitForUpdate(permitId);
        for (String permitKey : receivedKeys) {
            observationRepository.updateStatus(permitKey, ObservationStatus.PERMIT_REVIEW);
            snapshotRepository.insert(permitKey, ObservationStatus.RECEIVED.name(),
                    ObservationStatus.PERMIT_REVIEW.name(), "PERMIT_REVOKED");
        }
        return PermitResponse.of(permitRepository.findById(permitId).orElseThrow());
    }

    // ---------- 提交与合并写事务 ----------

    /**
     * 提交观测事务：同键重放返回原结果；命中唯一有效许可、版本一致且计数不超上限才接收，
     * 任一失败抛 422（携带拒绝记录草稿），不占用 permitKey。
     */
    @Transactional
    public SubmitOutcome submitObservationTx(SubmitObservationRequest request) {
        String permitKey = permitKey(request);
        Optional<SamplingObservation> existing = observationRepository.findByKey(permitKey);
        if (existing.isPresent()) {
            return new SubmitOutcome(201, readSubmitBody(existing.get().responseBody()));
        }

        if (outOfBounds(request.gridX()) || outOfBounds(request.gridY())) {
            throw singleRejection(permitKey, request, SamplingReasons.GRID_OUT_OF_BOUNDS,
                    "grid coordinate out of bounds [0," + MAX_GRID_COORDINATE + "]: ("
                            + request.gridX() + "," + request.gridY() + ")");
        }
        List<SamplingPermit> hits = permitRepository.findHits(request.deviceId(),
                request.gridX(), request.gridY(), request.collectedAtUtc());
        if (hits.isEmpty()) {
            throw singleRejection(permitKey, request, SamplingReasons.NO_PERMIT,
                    "no valid permit for device " + request.deviceId() + " at ("
                            + request.gridX() + "," + request.gridY() + ") " + request.collectedAtUtc());
        }
        if (hits.size() > 1) {
            throw singleRejection(permitKey, request, SamplingReasons.PERMIT_NOT_UNIQUE,
                    "multiple valid permits hit: " + hits.stream().map(SamplingPermit::permitId).toList());
        }
        SamplingPermit permit = permitRepository.findByIdForUpdate(hits.get(0).permitId()).orElseThrow();
        if (permit.permitVersion() != request.permitVersion()) {
            throw singleRejection(permitKey, request, SamplingReasons.PERMIT_VERSION_MISMATCH,
                    "permit version mismatch: submitted " + request.permitVersion()
                            + " but permit " + permit.permitId() + " is " + permit.permitVersion());
        }
        if (permit.usedCount() + 1 > permit.maxObservations()) {
            throw singleRejection(permitKey, request, SamplingReasons.PERMIT_LIMIT_EXCEEDED,
                    "permit " + permit.permitId() + " limit reached: " + permit.usedCount()
                            + "/" + permit.maxObservations());
        }

        SubmitResponse body = new SubmitResponse(permitKey, request.deviceId(), request.gridX(), request.gridY(),
                request.collectedAtUtc(), permit.permitId(), permit.permitVersion(),
                ObservationStatus.RECEIVED.name(), permit.usedCount() + 1);
        try {
            observationRepository.insert(new SamplingObservation(permitKey, request.deviceId(),
                    request.gridX(), request.gridY(), request.collectedAtUtc(), request.content(),
                    permit.permitId(), permit.permitVersion(), ObservationStatus.RECEIVED,
                    null, null, writeSubmitBody(body)));
        } catch (DuplicateKeyException e) {
            // 并发同键：由主键串行化，后到者返回先提交者的结果
            SamplingObservation winner = observationRepository.findByKey(permitKey)
                    .orElseThrow(() -> SamplingException.conflict("PERMIT_KEY_CONFLICT",
                            "permitKey conflict: " + permitKey));
            return new SubmitOutcome(201, readSubmitBody(winner.responseBody()));
        }
        permitRepository.addUsedCount(permit.permitId(), 1);
        return new SubmitOutcome(201, body);
    }

    /**
     * 批量离线合并事务：先预校验（坐标边界、许可命中唯一性、许可版本、完整最终计数），
     * 任一失败 422 且全部观测与簇变化回滚；通过后写入观测、冲突簇并更新许可计数。
     */
    @Transactional
    public BatchMergeOutcome mergeBatchTx(BatchMergeRequest request) {
        String fingerprint = fingerprint("BATCH", canonicalBatch(request));
        Optional<MergeBatchEntry> existing = mergeBatchRepository.find(request.batchId());
        if (existing.isPresent()) {
            MergeBatchEntry entry = existing.get();
            if (!entry.fingerprint().equals(fingerprint)) {
                throw SamplingException.conflict(SamplingReasons.BATCH_REUSED,
                        "batchId reused with different parameters: " + request.batchId());
            }
            return new BatchMergeOutcome(entry.responseStatus(), readBatchBody(entry.responseBody()));
        }
        try {
            mergeBatchRepository.insertPlaceholder(request.batchId(), fingerprint);
        } catch (DuplicateKeyException e) {
            MergeBatchEntry entry = mergeBatchRepository.find(request.batchId())
                    .orElseThrow(() -> SamplingException.conflict(SamplingReasons.BATCH_REUSED,
                            "batchId conflict: " + request.batchId()));
            if (!entry.fingerprint().equals(fingerprint)) {
                throw SamplingException.conflict(SamplingReasons.BATCH_REUSED,
                        "batchId reused with different parameters: " + request.batchId());
            }
            return new BatchMergeOutcome(entry.responseStatus(), readBatchBody(entry.responseBody()));
        }

        List<SubmitObservationRequest> items = request.observations();
        List<RejectionDraft> drafts = new ArrayList<>();

        // 预校验一：坐标边界
        for (SubmitObservationRequest item : items) {
            if (outOfBounds(item.gridX()) || outOfBounds(item.gridY())) {
                drafts.add(batchDraft(request.batchId(), item, SamplingReasons.GRID_OUT_OF_BOUNDS,
                        "grid coordinate out of bounds [0," + MAX_GRID_COORDINATE + "]: ("
                                + item.gridX() + "," + item.gridY() + ")"));
            }
        }

        // 预校验二：许可命中唯一性与许可版本
        Map<Integer, SamplingPermit> hitByIndex = new LinkedHashMap<>();
        if (drafts.isEmpty()) {
            for (int i = 0; i < items.size(); i++) {
                SubmitObservationRequest item = items.get(i);
                List<SamplingPermit> hits = permitRepository.findHits(item.deviceId(),
                        item.gridX(), item.gridY(), item.collectedAtUtc());
                if (hits.isEmpty()) {
                    drafts.add(batchDraft(request.batchId(), item, SamplingReasons.NO_PERMIT,
                            "no valid permit for device " + item.deviceId() + " at ("
                                    + item.gridX() + "," + item.gridY() + ") " + item.collectedAtUtc()));
                } else if (hits.size() > 1) {
                    drafts.add(batchDraft(request.batchId(), item, SamplingReasons.PERMIT_NOT_UNIQUE,
                            "multiple valid permits hit: "
                                    + hits.stream().map(SamplingPermit::permitId).toList()));
                } else if (hits.get(0).permitVersion() != item.permitVersion()) {
                    drafts.add(batchDraft(request.batchId(), item, SamplingReasons.PERMIT_VERSION_MISMATCH,
                            "permit version mismatch: submitted " + item.permitVersion()
                                    + " but permit " + hits.get(0).permitId() + " is "
                                    + hits.get(0).permitVersion()));
                } else {
                    hitByIndex.put(i, hits.get(0));
                }
            }
        }

        // 预校验三：完整最终计数（已存在键与批内重复键不计入新增）
        List<String> keys = items.stream().map(SamplingService::permitKey).toList();
        Set<String> existingKeys = observationRepository.findExistingKeys(keys);
        Map<String, Integer> newCountByPermit = new LinkedHashMap<>();
        if (drafts.isEmpty()) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < items.size(); i++) {
                String key = keys.get(i);
                if (existingKeys.contains(key) || !seen.add(key)) {
                    continue;
                }
                newCountByPermit.merge(hitByIndex.get(i).permitId(), 1, Integer::sum);
            }
            List<String> permitIds = new ArrayList<>(newCountByPermit.keySet());
            Collections.sort(permitIds);
            for (String permitId : permitIds) {
                SamplingPermit locked = permitRepository.findByIdForUpdate(permitId).orElseThrow();
                int incoming = newCountByPermit.get(permitId);
                int finalCount = locked.usedCount() + incoming;
                if (finalCount > locked.maxObservations()) {
                    drafts.add(new RejectionDraft(RejectionScope.BATCH.name(), request.batchId(),
                            locked.deviceId(), null, null, null, SamplingReasons.PERMIT_LIMIT_EXCEEDED,
                            "permit " + permitId + " final count " + finalCount
                                    + " exceeds max " + locked.maxObservations()
                                    + " (used " + locked.usedCount() + ", incoming " + incoming + ")"));
                }
            }
        }

        if (!drafts.isEmpty()) {
            throw SamplingException.unprocessable(drafts.get(0).reason(),
                    "batch pre-validation failed with " + drafts.size() + " error(s); first: "
                            + drafts.get(0).message(),
                    drafts);
        }

        // 写入：跳过已存在键与批内重复键（计为重放，不重复计数）
        Set<String> seen = new HashSet<>();
        List<Integer> newIndices = new ArrayList<>();
        int replayed = 0;
        for (int i = 0; i < items.size(); i++) {
            String key = keys.get(i);
            if (existingKeys.contains(key) || !seen.add(key)) {
                replayed++;
                continue;
            }
            newIndices.add(i);
        }

        // 冲突簇：同设备同网格单元的新观测内容不一致时成簇，按确定顺序编号
        Map<String, List<Integer>> groups = new TreeMap<>();
        for (int i : newIndices) {
            SubmitObservationRequest item = items.get(i);
            groups.computeIfAbsent(item.deviceId() + SEPARATOR + item.gridX() + SEPARATOR + item.gridY(),
                    k -> new ArrayList<>()).add(i);
        }
        Map<Integer, String> clusterByIndex = new HashMap<>();
        List<String> conflictClusterIds = new ArrayList<>();
        int clusterSeq = 0;
        for (List<Integer> members : groups.values()) {
            long distinctContents = members.stream().map(i -> items.get(i).content()).distinct().count();
            if (distinctContents > 1) {
                String clusterId = request.batchId() + "-c" + (++clusterSeq);
                SubmitObservationRequest first = items.get(members.get(0));
                clusterRepository.insert(new ConflictCluster(clusterId, request.batchId(), first.deviceId(),
                        first.gridX(), first.gridY(), ClusterStatus.PENDING, null, null, null));
                conflictClusterIds.add(clusterId);
                for (int i : members) {
                    clusterByIndex.put(i, clusterId);
                }
            }
        }

        // 插入观测并更新许可计数
        Map<String, Integer> permitCounts = new TreeMap<>();
        int merged = 0;
        for (int i : newIndices) {
            SubmitObservationRequest item = items.get(i);
            SamplingPermit permit = hitByIndex.get(i);
            String clusterId = clusterByIndex.get(i);
            ObservationStatus status = clusterId == null ? ObservationStatus.MERGED : ObservationStatus.RECEIVED;
            if (clusterId == null) {
                merged++;
            }
            SubmitResponse itemBody = new SubmitResponse(keys.get(i), item.deviceId(), item.gridX(), item.gridY(),
                    item.collectedAtUtc(), permit.permitId(), permit.permitVersion(), status.name(), null);
            observationRepository.insert(new SamplingObservation(keys.get(i), item.deviceId(),
                    item.gridX(), item.gridY(), item.collectedAtUtc(), item.content(),
                    permit.permitId(), permit.permitVersion(), status,
                    request.batchId(), clusterId, writeSubmitBody(itemBody)));
        }
        for (Map.Entry<String, Integer> entry : newCountByPermit.entrySet()) {
            permitRepository.addUsedCount(entry.getKey(), entry.getValue());
            SamplingPermit updated = permitRepository.findById(entry.getKey()).orElseThrow();
            permitCounts.put(entry.getKey(), updated.usedCount());
        }

        BatchMergeResponse body = new BatchMergeResponse(request.batchId(), newIndices.size(), replayed, merged,
                List.copyOf(conflictClusterIds), permitCounts);
        mergeBatchRepository.complete(request.batchId(), 200, writeBatchBody(body));
        return new BatchMergeOutcome(200, body);
    }

    /**
     * 冲突簇人工裁决事务：仅待裁决簇可裁决；同参重放返回原结果，异参 409。
     * 裁决后成员观测统一置为已合并，簇不再重算。
     */
    @Transactional
    public ClusterResponse adjudicateClusterTx(String clusterId, AdjudicateClusterRequest request) {
        ConflictCluster cluster = clusterRepository.findByIdForUpdate(clusterId)
                .orElseThrow(() -> SamplingException.notFound("cluster not found: " + clusterId));
        if (cluster.status() == ClusterStatus.ADJUDICATED) {
            if (Objects.equals(cluster.winningContent(), request.winningContent())
                    && Objects.equals(cluster.adjudicatedBy(), request.operator())) {
                return ClusterResponse.of(cluster);
            }
            throw SamplingException.conflict(SamplingReasons.CLUSTER_NOT_PENDING,
                    "cluster already adjudicated: " + clusterId);
        }
        Instant adjudicatedAtUtc = Instant.now(clock);
        clusterRepository.markAdjudicated(clusterId, request.winningContent(), request.operator(), adjudicatedAtUtc);
        observationRepository.updateStatusForCluster(clusterId, ObservationStatus.MERGED);
        return ClusterResponse.of(clusterRepository.findById(clusterId).orElseThrow());
    }

    /**
     * 拒绝记录落库：业务事务回滚后以独立事务追加写入，失败不占用 permitKey/batchId。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejections(List<RejectionDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        Instant rejectedAtUtc = Instant.now(clock);
        for (RejectionDraft draft : drafts) {
            rejectionRepository.insert(draft, rejectedAtUtc);
        }
    }

    // ---------- 查询 ----------

    /**
     * 查询许可配置与实时计数；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public PermitResponse getPermit(String permitId) {
        return PermitResponse.of(permitRepository.findById(permitId)
                .orElseThrow(() -> SamplingException.notFound("permit not found: " + permitId)));
    }

    /**
     * 按幂等键查询观测（含许可版本与复核标记）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public SamplingObservationResponse getObservation(String permitKey) {
        return SamplingObservationResponse.of(observationRepository.findByKey(permitKey)
                .orElseThrow(() -> SamplingException.notFound("observation not found: " + permitKey)));
    }

    /**
     * 查询许可下全部观测；许可不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<SamplingObservationResponse> listPermitObservations(String permitId) {
        permitRepository.findById(permitId)
                .orElseThrow(() -> SamplingException.notFound("permit not found: " + permitId));
        return observationRepository.findByPermitId(permitId).stream()
                .map(SamplingObservationResponse::of)
                .toList();
    }

    /**
     * 查询拒绝记录：设备标识与请求键均可选过滤。
     */
    @Transactional(readOnly = true)
    public List<RejectionResponse> listRejections(String deviceId, String requestKey) {
        return rejectionRepository.find(deviceId, requestKey).stream()
                .map(RejectionResponse::of)
                .toList();
    }

    /**
     * 按标识查询冲突簇；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ClusterResponse getCluster(String clusterId) {
        return ClusterResponse.of(clusterRepository.findById(clusterId)
                .orElseThrow(() -> SamplingException.notFound("cluster not found: " + clusterId)));
    }

    // ---------- 内部辅助 ----------

    /**
     * 计算提交幂等键：设备、许可版本、网格、采集时刻与观测内容的 SHA-256 指纹。
     */
    private static String permitKey(SubmitObservationRequest request) {
        return fingerprint("SUBMIT", request.deviceId(), String.valueOf(request.permitVersion()),
                String.valueOf(request.gridX()), String.valueOf(request.gridY()),
                request.collectedAtUtc().toString(), request.content());
    }

    /**
     * 批次内容规范化表示：批次标识与全部条目按提交顺序拼接，作为指纹输入。
     */
    private static String[] canonicalBatch(BatchMergeRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(request.batchId());
        for (SubmitObservationRequest item : request.observations()) {
            parts.add(item.deviceId());
            parts.add(String.valueOf(item.permitVersion()));
            parts.add(String.valueOf(item.gridX()));
            parts.add(String.valueOf(item.gridY()));
            parts.add(item.collectedAtUtc().toString());
            parts.add(item.content());
        }
        return parts.toArray(new String[0]);
    }

    private static boolean outOfBounds(int coordinate) {
        return coordinate < 0 || coordinate > MAX_GRID_COORDINATE;
    }

    /**
     * 构造单次提交的 422 异常：携带一条拒绝记录草稿。
     */
    private static SamplingException singleRejection(String permitKey, SubmitObservationRequest request,
                                                     String reason, String message) {
        RejectionDraft draft = new RejectionDraft(RejectionScope.SINGLE.name(), permitKey,
                request.deviceId(), request.gridX(), request.gridY(), request.collectedAtUtc(), reason, message);
        return SamplingException.unprocessable(reason, message, List.of(draft));
    }

    /**
     * 构造批量合并条目的拒绝记录草稿。
     */
    private static RejectionDraft batchDraft(String batchId, SubmitObservationRequest item,
                                             String reason, String message) {
        return new RejectionDraft(RejectionScope.BATCH.name(), batchId,
                item.deviceId(), item.gridX(), item.gridY(), item.collectedAtUtc(), reason, message);
    }

    private static String fingerprint(String operation, String... parts) {
        StringBuilder raw = new StringBuilder(operation);
        for (String part : parts) {
            raw.append(SEPARATOR).append(part == null ? "<null>" : part);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(raw.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeSubmitBody(SubmitResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize submit response", e);
        }
    }

    private SubmitResponse readSubmitBody(String json) {
        try {
            return objectMapper.readValue(json, SubmitResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored submit response", e);
        }
    }

    private String writeBatchBody(BatchMergeResponse body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize batch response", e);
        }
    }

    private BatchMergeResponse readBatchBody(String json) {
        try {
            return objectMapper.readValue(json, BatchMergeResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored batch response", e);
        }
    }

    /**
     * 提交观测结果：HTTP 状态码与响应体。
     */
    public record SubmitOutcome(int status, SubmitResponse body) {
    }

    /**
     * 批量合并结果：HTTP 状态码与响应体。
     */
    public record BatchMergeOutcome(int status, BatchMergeResponse body) {
    }
}
