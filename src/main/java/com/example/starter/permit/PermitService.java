package com.example.starter.permit;

import com.example.starter.observation.ApiException;
import com.example.starter.observation.RequestLogEntry;
import com.example.starter.observation.RequestLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 观测采样许可业务服务：许可签发/结束/撤销、许可观测提交、批量离线合并与冲突簇裁决。
 *
 * <p>所有写操作复用观测域 request_log 占位-回填幂等机制：业务失败整体回滚、不占 requestId；
 * permitKey 指纹含设备、许可版本、网格、采集时刻与观测内容，同键同参重放原结果，失败不占键。
 * 拒绝原因另经独立事务写入 permit_rejection_log，业务回滚不影响原因留痕。
 */
@Service
public class PermitService {

    private static final String SEPARATOR = "";

    private final SamplingPermitRepository permitRepository;
    private final PermitObservationRepository observationRepository;
    private final ClusterResolutionRepository clusterResolutionRepository;
    private final RejectionLogRepository rejectionLogRepository;
    private final RequestLogRepository requestLogRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PermitService(SamplingPermitRepository permitRepository,
                         PermitObservationRepository observationRepository,
                         ClusterResolutionRepository clusterResolutionRepository,
                         RejectionLogRepository rejectionLogRepository,
                         RequestLogRepository requestLogRepository,
                         ObjectMapper objectMapper,
                         Clock clock) {
        this.permitRepository = permitRepository;
        this.observationRepository = observationRepository;
        this.clusterResolutionRepository = clusterResolutionRepository;
        this.rejectionLogRepository = rejectionLogRepository;
        this.requestLogRepository = requestLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 许可管理 ----------

    /**
     * 签发采样许可：permitVersion 须为该设备下一个版本号，validFrom 不得早于当前时刻（新许可不可追溯），
     * 与同设备许可在网格与时间交集重叠时返回 409（PERMIT_OVERLAP）。
     */
    @Transactional
    public PermitOutcome createPermit(CreatePermitRequest request) {
        Instant now = Instant.now(clock);
        String fingerprint = fingerprint("PERMIT_CREATE", request.permitId(), request.deviceId(),
                String.valueOf(request.permitVersion()), request.gridMinLon().toPlainString(),
                request.gridMinLat().toPlainString(), request.gridMaxLon().toPlainString(),
                request.gridMaxLat().toPlainString(), request.validFrom().toString(), request.validTo().toString(),
                String.valueOf(request.maxObservations()));
        PermitOutcome replayed = replayPermit(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        PermitOutcome occupied = occupyPermit(request.requestId(), fingerprint, "PERMIT_CREATE");
        if (occupied != null) {
            return occupied;
        }

        validatePermitGeometryAndWindow(request, now);
        // 设备行锁串行化同设备并发签发，矩形相交判定无需依赖数据库区间约束。
        permitRepository.lockDevice(request.deviceId());
        if (permitRepository.findById(request.permitId()).isPresent()) {
            throw conflictAudited(request.requestId(), now, request.deviceId(),
                    PermitReasons.PERMIT_ID_DUPLICATE,
                    "permit already exists: " + request.permitId());
        }
        int maxVersion = permitRepository.findByDevice(request.deviceId()).stream()
                .mapToInt(SamplingPermit::permitVersion).max().orElse(0);
        if (request.permitVersion() != maxVersion + 1) {
            throw conflictAudited(request.requestId(), now, request.deviceId(),
                    PermitReasons.PERMIT_VERSION_DUPLICATE,
                    "permitVersion must be the next version for device, expected " + (maxVersion + 1)
                            + " but got " + request.permitVersion());
        }
        SamplingPermit candidate = new SamplingPermit(
                request.permitId(), request.deviceId(), request.permitVersion(),
                request.gridMinLon(), request.gridMinLat(), request.gridMaxLon(), request.gridMaxLat(),
                request.validFrom(), request.validTo(), null, request.maxObservations(), 0,
                SamplingPermit.ACTIVE, null, null);
        List<SamplingPermit> neighbors = permitRepository.findDevicePermitsForUpdate(
                request.deviceId(), request.validFrom(), request.validTo());
        for (SamplingPermit existing : neighbors) {
            if (existing.overlaps(candidate)) {
                throw conflictAudited(request.requestId(), now, request.deviceId(),
                        PermitReasons.PERMIT_OVERLAP,
                        "permit overlaps existing permit " + existing.permitId()
                                + " in grid and time intersection");
            }
        }
        try {
            permitRepository.insert(candidate);
        } catch (DuplicateKeyException e) {
            throw conflictAudited(request.requestId(), now, request.deviceId(),
                    PermitReasons.PERMIT_ID_DUPLICATE,
                    "permit already exists: " + request.permitId());
        }
        return completePermit(request.requestId(), HttpStatus.CREATED, PermitResponse.of(candidate));
    }

    /**
     * 许可提前结束：只影响结束时刻之后的采样，已接收观测与计数不变；非 ACTIVE 状态返回 409。
     */
    @Transactional
    public PermitOutcome endPermit(String permitId, EndPermitRequest request) {
        Instant now = Instant.now(clock);
        Instant endAt = request.endAt() == null ? now : request.endAt();
        String fingerprint = fingerprint("PERMIT_END", permitId, endAt.toString());
        PermitOutcome replayed = replayPermit(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        PermitOutcome occupied = occupyPermit(request.requestId(), fingerprint, "PERMIT_END");
        if (occupied != null) {
            return occupied;
        }
        if (endAt.isAfter(now)) {
            throw ApiException.badRequest("endAt must not be in the future");
        }

        SamplingPermit permit = permitRepository.findByIdForUpdate(permitId)
                .orElseThrow(() -> ApiException.notFound("permit not found: " + permitId,
                        PermitReasons.PERMIT_NOT_FOUND));
        if (SamplingPermit.ENDED.equals(permit.status())) {
            throw ApiException.conflictWithReason("permit already ended: " + permitId,
                    PermitReasons.PERMIT_ALREADY_ENDED);
        }
        if (SamplingPermit.REVOKED.equals(permit.status())) {
            throw ApiException.conflictWithReason("permit already revoked: " + permitId,
                    PermitReasons.PERMIT_ALREADY_REVOKED);
        }
        Instant effectiveEnd = endAt.isBefore(permit.validFrom()) ? permit.validFrom() : endAt;
        permitRepository.updateState(permitId, SamplingPermit.ENDED, effectiveEnd);
        SamplingPermit updated = permitRepository.findById(permitId).orElseThrow();
        return completePermit(request.requestId(), HttpStatus.OK, PermitResponse.of(updated));
    }

    /**
     * 撤销许可：自当前时刻起失效；撤销时刻未合并（RECEIVED）观测标记 PERMIT_REVIEW 并逐行写入快照，
     * 快照清单同时写入许可；已合并观测与已人工裁决簇不重算。
     */
    @Transactional
    public PermitOutcome revokePermit(String permitId, RevokePermitRequest request) {
        Instant now = Instant.now(clock);
        String fingerprint = fingerprint("PERMIT_REVOKE", permitId);
        PermitOutcome replayed = replayPermit(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        PermitOutcome occupied = occupyPermit(request.requestId(), fingerprint, "PERMIT_REVOKE");
        if (occupied != null) {
            return occupied;
        }

        SamplingPermit permit = permitRepository.findByIdForUpdate(permitId)
                .orElseThrow(() -> ApiException.notFound("permit not found: " + permitId,
                        PermitReasons.PERMIT_NOT_FOUND));
        if (SamplingPermit.REVOKED.equals(permit.status())) {
            throw ApiException.conflictWithReason("permit already revoked: " + permitId,
                    PermitReasons.PERMIT_ALREADY_REVOKED);
        }
        if (SamplingPermit.ENDED.equals(permit.status())) {
            throw ApiException.conflictWithReason("permit already ended: " + permitId,
                    PermitReasons.PERMIT_ALREADY_ENDED);
        }

        List<PermitObservation> pending = observationRepository.findReceivedByPermitForUpdate(permitId);
        List<PermitObservationResponse> snapshotItems = new ArrayList<>();
        for (PermitObservation observation : pending) {
            observationRepository.updateReview(observation.observationKey());
            PermitObservation reviewed = new PermitObservation(
                    observation.observationKey(), observation.deviceId(), observation.gridLon(),
                    observation.gridLat(), observation.sampledAt(), observation.permitId(),
                    observation.permitVersion(), observation.content(),
                    PermitReviewStatuses.PERMIT_REVIEW, observation.mergedClusterId(),
                    observation.rejectReasonCode(), observation.receivedAt());
            int nextVersion = observationRepository.currentVersion(observation.observationKey()) + 1;
            observationRepository.insertVersion(reviewed, nextVersion);
            snapshotItems.add(PermitObservationResponse.of(reviewed));
        }
        String snapshotJson = writeJson(snapshotItems);
        permitRepository.updateRevoked(permitId, now, snapshotJson, now);
        SamplingPermit updated = permitRepository.findById(permitId).orElseThrow();
        return completePermit(request.requestId(), HttpStatus.OK, PermitResponse.of(updated));
    }

    /**
     * 查询许可及其配额计数；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public PermitResponse getPermit(String permitId) {
        return PermitResponse.of(permitRepository.findById(permitId)
                .orElseThrow(() -> ApiException.notFound("permit not found: " + permitId,
                        PermitReasons.PERMIT_NOT_FOUND)));
    }

    // ---------- 单条提交 ----------

    /**
     * 提交单条观测：设备、采集网格、采集时刻必须唯一命中有效许可，条件式扣减该许可计数且不超过上限；
     * 无许可/命中不唯一/超限分别以可区分原因码返回 422，失败不写入任何半成品。
     */
    @Transactional
    public SubmitOutcome submit(SubmitObservationRequest request) {
        Instant now = Instant.now(clock);
        String key = observationKey(request.deviceId(), request.gridLon(), request.gridLat(), request.sampledAt());
        String fingerprint = fingerprint("PERMIT_SUBMIT", request.deviceId(), request.gridLon().toPlainString(),
                request.gridLat().toPlainString(), request.sampledAt().toString(), request.content());
        SubmitOutcome replayed = replaySubmit(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        SubmitOutcome occupied = occupySubmit(request.requestId(), fingerprint, "PERMIT_SUBMIT");
        if (occupied != null) {
            return occupied;
        }

        if (observationRepository.findByKeyForUpdate(key).isPresent()) {
            throw reject422(request.requestId(), "SUBMIT", key, request.deviceId(),
                    request.gridLon(), request.gridLat(), request.sampledAt(), now,
                    PermitReasons.OBSERVATION_ALREADY_RECEIVED,
                    "observation already received: " + key);
        }
        SamplingPermit permit = resolveUniquePermit(request.requestId(), "SUBMIT", key,
                request.deviceId(), request.gridLon(), request.gridLat(), request.sampledAt(), now);
        // permitKey 完整指纹含命中许可版本：重放短路只比对请求参数指纹，但落库完整指纹必须含版本。
        String permitKeyFingerprint = fingerprint("PERMIT_SUBMIT", request.deviceId(),
                String.valueOf(permit.permitVersion()), request.gridLon().toPlainString(),
                request.gridLat().toPlainString(), request.sampledAt().toString(), request.content());
        requestLogRepository.updateFingerprint(request.requestId(), permitKeyFingerprint);
        if (!permitRepository.incrementCount(permit.permitId(), 1)) {
            throw reject422(request.requestId(), "SUBMIT", key, request.deviceId(),
                    request.gridLon(), request.gridLat(), request.sampledAt(), now,
                    PermitReasons.QUOTA_EXCEEDED,
                    "permit quota exhausted: " + permit.permitId());
        }
        PermitObservation observation = new PermitObservation(
                key, request.deviceId(), request.gridLon(), request.gridLat(), request.sampledAt(),
                permit.permitId(), permit.permitVersion(), request.content(),
                PermitReviewStatuses.RECEIVED, null, null, now);
        try {
            observationRepository.insertCurrent(observation);
        } catch (DuplicateKeyException e) {
            // 并发提交同一观测业务键：由主键串行化，后到者按已接收处理。
            throw reject422(request.requestId(), "SUBMIT", key, request.deviceId(),
                    request.gridLon(), request.gridLat(), request.sampledAt(), now,
                    PermitReasons.OBSERVATION_ALREADY_RECEIVED,
                    "observation already received: " + key);
        }
        observationRepository.insertVersion(observation, 1);
        return completeSubmit(request.requestId(), HttpStatus.CREATED, PermitObservationResponse.of(observation));
    }

    // ---------- 批量离线合并 ----------

    /**
     * 批量离线合并：任何写入前完成全部观测的唯一许可命中、坐标边界、按许可汇总的完整最终计数与冲突簇预校验，
     * 任一不满足整批 422 且全部观测与簇变化回滚。
     */
    @Transactional
    public BatchOutcome batchMerge(BatchMergeRequest request) {
        Instant now = Instant.now(clock);
        List<BatchMergeRequest.MergeItem> items = request.items();
        String itemSignature = items.stream()
                .map(item -> String.join("/", item.deviceId(), item.gridLon().toPlainString(),
                        item.gridLat().toPlainString(), item.sampledAt().toString(), item.content()))
                .reduce("", (acc, part) -> acc + SEPARATOR + part);
        String clusterSignature = request.clusters().stream()
                .map(cluster -> cluster.clusterId() + "=" + String.join(",", cluster.observationKeys()))
                .reduce("", (acc, part) -> acc + SEPARATOR + part);
        String fingerprint = fingerprint("PERMIT_BATCH", String.valueOf(items.size()),
                itemSignature, clusterSignature);
        BatchOutcome replayed = replayBatch(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        BatchOutcome occupied = occupyBatch(request.requestId(), fingerprint, "PERMIT_BATCH");
        if (occupied != null) {
            return occupied;
        }

        // 1) 业务键计算与批次内重复、未来采集时刻校验。
        Map<String, BatchMergeRequest.MergeItem> itemByKey = new LinkedHashMap<>();
        for (BatchMergeRequest.MergeItem item : items) {
            String key = observationKey(item.deviceId(), item.gridLon(), item.gridLat(), item.sampledAt());
            if (itemByKey.put(key, item) != null) {
                throw reject422(request.requestId(), "BATCH_MERGE", null, null, null, null, null, now,
                        PermitReasons.DUPLICATE_IN_BATCH,
                        "duplicate observation in batch: " + key);
            }
            if (item.sampledAt().isAfter(now)) {
                throw reject422(request.requestId(), "BATCH_MERGE", key, item.deviceId(),
                        item.gridLon(), item.gridLat(), item.sampledAt(), now,
                        PermitReasons.SAMPLED_AT_IN_FUTURE,
                        "sampledAt is in the future: " + key);
            }
        }

        // 2) 冲突簇结构校验：簇标识批次内唯一、成员键均在批次内且不跨簇、未被历史批次或裁决占用。
        Map<String, String> clusterByKey = new HashMap<>();
        Set<String> clusterIds = new HashSet<>();
        for (BatchMergeRequest.ClusterSpec cluster : request.clusters()) {
            if (!clusterIds.add(cluster.clusterId())) {
                throw reject422(request.requestId(), "BATCH_MERGE", null, null, null, null, null, now,
                        PermitReasons.DUPLICATE_IN_BATCH,
                        "duplicate clusterId in batch: " + cluster.clusterId());
            }
            if (clusterResolutionRepository.findByClusterId(cluster.clusterId()).isPresent()
                    || observationRepository.clusterReferenced(cluster.clusterId())) {
                throw reject422(request.requestId(), "BATCH_MERGE", null, null, null, null, null, now,
                        PermitReasons.CLUSTER_ALREADY_MERGED,
                        "cluster already merged or resolved: " + cluster.clusterId());
            }
            Set<String> memberKeys = new HashSet<>(cluster.observationKeys());
            if (memberKeys.size() != cluster.observationKeys().size()) {
                throw reject422(request.requestId(), "BATCH_MERGE", null, null, null, null, null, now,
                        PermitReasons.DUPLICATE_IN_BATCH,
                        "duplicate member key in cluster: " + cluster.clusterId());
            }
            for (String memberKey : cluster.observationKeys()) {
                if (!itemByKey.containsKey(memberKey)) {
                    throw reject422(request.requestId(), "BATCH_MERGE", memberKey, null, null, null, null, now,
                            PermitReasons.WINNER_NOT_IN_CLUSTER,
                            "cluster member not present in batch items: " + memberKey);
                }
                if (clusterByKey.put(memberKey, cluster.clusterId()) != null) {
                    throw reject422(request.requestId(), "BATCH_MERGE", memberKey, null, null, null, null, now,
                            PermitReasons.DUPLICATE_IN_BATCH,
                            "observation belongs to multiple clusters: " + memberKey);
                }
            }
        }

        // 3) 逐条唯一命中许可（候选许可行加锁），并按许可汇总增量计数。
        Map<String, SamplingPermit> permits = new LinkedHashMap<>();
        Map<String, Integer> deltaByPermit = new LinkedHashMap<>();
        Map<String, String> permitByKey = new LinkedHashMap<>();
        StringBuilder permitVersionSignature = new StringBuilder();
        for (Map.Entry<String, BatchMergeRequest.MergeItem> entry : itemByKey.entrySet()) {
            String key = entry.getKey();
            BatchMergeRequest.MergeItem item = entry.getValue();
            if (observationRepository.findByKeyForUpdate(key).isPresent()) {
                throw reject422(request.requestId(), "BATCH_MERGE", key, item.deviceId(),
                        item.gridLon(), item.gridLat(), item.sampledAt(), now,
                        PermitReasons.OBSERVATION_ALREADY_RECEIVED,
                        "observation already received: " + key);
            }
            SamplingPermit permit = resolveUniquePermit(request.requestId(), "BATCH_MERGE", key,
                    item.deviceId(), item.gridLon(), item.gridLat(), item.sampledAt(), now);
            permits.put(permit.permitId(), permit);
            deltaByPermit.merge(permit.permitId(), 1, Integer::sum);
            permitByKey.put(key, permit.permitId());
            permitVersionSignature.append(SEPARATOR).append(key).append('=').append(permit.permitVersion());
        }
        // 完整 permitKey 指纹含每条观测命中的许可版本；重放短路仍只比对请求参数指纹。
        requestLogRepository.updateFingerprint(request.requestId(),
                fingerprint("PERMIT_BATCH", String.valueOf(items.size()),
                        itemSignature, clusterSignature, permitVersionSignature.toString()));

        // 4) 完整最终计数预校验：基线为锁内重读的已接收计数。
        Map<String, Integer> finalCountByPermit = new LinkedHashMap<>();
        for (String permitId : permits.keySet()) {
            SamplingPermit locked = permitRepository.findByIdForUpdate(permitId).orElseThrow();
            int delta = deltaByPermit.get(permitId);
            if (locked.acceptedCount() + delta > locked.maxObservations()) {
                throw reject422(request.requestId(), "BATCH_MERGE", null, null, null, null, null, now,
                        PermitReasons.QUOTA_EXCEEDED,
                        "batch final count " + (locked.acceptedCount() + delta)
                                + " exceeds permit max " + locked.maxObservations() + ": " + permitId);
            }
            finalCountByPermit.put(permitId, locked.acceptedCount() + delta);
        }

        // 5) 全部预校验通过后整批写入：逐条扣减计数，接收并直接置为 MERGED（携带冲突簇归属）。
        List<PermitObservationResponse> responses = new ArrayList<>();
        for (Map.Entry<String, BatchMergeRequest.MergeItem> entry : itemByKey.entrySet()) {
            String key = entry.getKey();
            BatchMergeRequest.MergeItem item = entry.getValue();
            String permitId = permitByKey.get(key);
            SamplingPermit permit = permits.get(permitId);
            if (!permitRepository.incrementCount(permitId, 1)) {
                // 锁内预校验已保证不超限：此处失败说明并发破坏了串行化假设，整批回滚。
                throw reject422(request.requestId(), "BATCH_MERGE", key, item.deviceId(),
                        item.gridLon(), item.gridLat(), item.sampledAt(), now,
                        PermitReasons.QUOTA_EXCEEDED, "permit quota exhausted: " + permitId);
            }
            PermitObservation observation = new PermitObservation(
                    key, item.deviceId(), item.gridLon(), item.gridLat(), item.sampledAt(),
                    permitId, permit.permitVersion(), item.content(),
                    PermitReviewStatuses.MERGED, clusterByKey.get(key), null, now);
            try {
                observationRepository.insertCurrent(observation);
            } catch (DuplicateKeyException e) {
                // 并发下该观测已被其他事务接收：整批回滚，不留部分批次结果。
                throw reject422(request.requestId(), "BATCH_MERGE", key, item.deviceId(),
                        item.gridLon(), item.gridLat(), item.sampledAt(), now,
                        PermitReasons.OBSERVATION_ALREADY_RECEIVED,
                        "observation already received: " + key);
            }
            observationRepository.insertVersion(observation, 1);
            responses.add(PermitObservationResponse.of(observation));
        }

        BatchMergeResponse body = new BatchMergeResponse(
                responses.size(), List.copyOf(responses),
                request.clusters().stream().map(BatchMergeRequest.ClusterSpec::clusterId).toList(),
                Map.copyOf(finalCountByPermit));
        return completeBatch(request.requestId(), HttpStatus.OK, body);
    }

    // ---------- 查询与裁决 ----------

    /**
     * 查询许可观测：含命中许可版本、复核标记与该坐标最近一次拒绝原因码；观测与拒绝记录均不存在时 404。
     */
    @Transactional(readOnly = true)
    public PermitObservationResponse getObservation(String observationKey) {
        PermitObservation observation = observationRepository.findByKey(observationKey).orElse(null);
        String latestReason = rejectionLogRepository.findByObservationKey(observationKey).stream()
                .map(RejectionEntry::reasonCode).findFirst().orElse(null);
        if (observation == null) {
            if (latestReason != null) {
                // 观测从未接收成功：返回仅含拒绝原因的审计视图不可用，统一 404 并由拒绝历史接口查询原因。
                throw ApiException.notFound("permit observation not found: " + observationKey
                        + " (last rejection: " + latestReason + ")", PermitReasons.OBSERVATION_NOT_FOUND);
            }
            throw ApiException.notFound("permit observation not found: " + observationKey,
                    PermitReasons.OBSERVATION_NOT_FOUND);
        }
        PermitObservation withReason = new PermitObservation(
                observation.observationKey(), observation.deviceId(), observation.gridLon(),
                observation.gridLat(), observation.sampledAt(), observation.permitId(),
                observation.permitVersion(), observation.content(), observation.reviewStatus(),
                observation.mergedClusterId(), latestReason, observation.receivedAt());
        return PermitObservationResponse.of(withReason);
    }

    /**
     * 查询某观测坐标的全部拒绝原因（按拒绝时刻倒序）；观测尚未接收成功时仍可查询其拒绝历史。
     */
    @Transactional(readOnly = true)
    public List<RejectionEntry> listRejections(String observationKey) {
        return rejectionLogRepository.findByObservationKey(observationKey);
    }

    /**
     * 冲突簇人工裁决：选中观测必须属于该簇；已裁决簇再次裁决返回 409 且不重算，同 requestId 同参重放原结果。
     */
    @Transactional
    public ClusterOutcome resolveCluster(String clusterId, ResolveClusterRequest request) {
        Instant now = Instant.now(clock);
        String fingerprint = fingerprint("PERMIT_RESOLVE_CLUSTER", clusterId,
                request.chosenObservationKey(), request.operator());
        ClusterOutcome replayed = replayCluster(request.requestId(), fingerprint);
        if (replayed != null) {
            return replayed;
        }
        ClusterOutcome occupied = occupyCluster(request.requestId(), fingerprint, "PERMIT_RESOLVE");
        if (occupied != null) {
            return occupied;
        }

        if (clusterResolutionRepository.findByClusterId(clusterId).isPresent()) {
            throw ApiException.conflictWithReason("cluster already resolved: " + clusterId,
                    PermitReasons.CLUSTER_ALREADY_RESOLVED);
        }
        List<PermitObservation> members = observationRepository.findByClusterForUpdate(clusterId);
        if (members.isEmpty()) {
            throw ApiException.notFound("cluster not found: " + clusterId, PermitReasons.CLUSTER_NOT_FOUND);
        }
        PermitObservation winner = members.stream()
                .filter(member -> member.observationKey().equals(request.chosenObservationKey()))
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable(
                        "chosen observation is not a member of cluster: " + clusterId,
                        PermitReasons.WINNER_NOT_IN_CLUSTER));

        ClusterResolution record = new ClusterResolution(
                clusterId, request.requestId(), members.stream().map(PermitObservation::observationKey).toList(),
                winner.observationKey(), winner.content(), winner.permitId(), request.operator(), now);
        clusterResolutionRepository.insert(record);
        ClusterResolutionResponse response = new ClusterResolutionResponse(
                record.clusterId(), record.requestId(), record.observationKeys(),
                record.chosenObservationKey(), record.chosenContent(), record.chosenPermitId(),
                record.operator(), record.resolvedAt());
        return completeCluster(request.requestId(), HttpStatus.OK, response);
    }

    /**
     * 查询冲突簇裁决记录；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ClusterResolutionResponse getClusterResolution(String clusterId) {
        ClusterResolution record = clusterResolutionRepository.findByClusterId(clusterId)
                .orElseThrow(() -> ApiException.notFound("cluster resolution not found: " + clusterId,
                        PermitReasons.CLUSTER_NOT_FOUND));
        return new ClusterResolutionResponse(
                record.clusterId(), record.requestId(), record.observationKeys(),
                record.chosenObservationKey(), record.chosenContent(), record.chosenPermitId(),
                record.operator(), record.resolvedAt());
    }

    // ---------- 许可命中与失败处理 ----------

    /**
     * 唯一命中判定：候选许可行加锁后按实际生效窗口（含提前结束/撤销截断）与网格闭矩形精判。
     * 未来采样、无时间有效许可、坐标越界、命中不唯一分别返回不同原因码的 422。
     */
    private SamplingPermit resolveUniquePermit(String requestId, String operation, String key,
                                               String deviceId, BigDecimal lon, BigDecimal lat,
                                               Instant sampledAt, Instant now) {
        if (sampledAt.isAfter(now)) {
            throw reject422(requestId, operation, key, deviceId, lon, lat, sampledAt, now,
                    PermitReasons.SAMPLED_AT_IN_FUTURE, "sampledAt is in the future: " + key);
        }
        List<SamplingPermit> candidates = permitRepository.findDeviceTimeCandidatesForUpdate(deviceId, sampledAt)
                .stream().filter(permit -> permit.timeCovers(sampledAt)).toList();
        if (candidates.isEmpty()) {
            throw reject422(requestId, operation, key, deviceId, lon, lat, sampledAt, now,
                    PermitReasons.NO_TIME_VALID_PERMIT,
                    "no time-valid permit for device at sampledAt: " + deviceId + " @ " + sampledAt);
        }
        List<SamplingPermit> covering = candidates.stream()
                .filter(permit -> permit.gridCovers(lon, lat)).toList();
        if (covering.isEmpty()) {
            throw reject422(requestId, operation, key, deviceId, lon, lat, sampledAt, now,
                    PermitReasons.GRID_OUT_OF_BOUNDS,
                    "sampling grid is outside all time-valid permit bounds: " + key);
        }
        if (covering.size() > 1) {
            throw reject422(requestId, operation, key, deviceId, lon, lat, sampledAt, now,
                    PermitReasons.PERMIT_NOT_UNIQUE,
                    "multiple permits cover device/grid/time: "
                            + covering.stream().map(SamplingPermit::permitId).toList());
        }
        return covering.get(0);
    }

    private void validatePermitGeometryAndWindow(CreatePermitRequest request, Instant now) {
        if (request.gridMinLon().compareTo(request.gridMaxLon()) > 0
                || request.gridMinLat().compareTo(request.gridMaxLat()) > 0) {
            throw ApiException.badRequest("grid minimum bounds must not exceed maximum bounds");
        }
        if (!request.validTo().isAfter(request.validFrom())) {
            throw ApiException.badRequest("validTo must be after validFrom");
        }
        if (request.validFrom().isBefore(now)) {
            // 新许可不能追溯使过去拒绝的请求成功：生效起点不得早于签发时刻。
            throw ApiException.badRequest("validFrom must not be in the past; new permits cannot be retroactive");
        }
    }

    /**
     * 记录 422 拒绝原因（独立事务留痕）后返回对应异常；调用方业务事务随抛出回滚，不留半成品。
     */
    private ApiException reject422(String requestId, String operation, String key,
                                   String deviceId, BigDecimal lon, BigDecimal lat, Instant sampledAt,
                                   Instant now, String reasonCode, String message) {
        rejectionLogRepository.append(requestId, operation, key, deviceId, lon, lat, sampledAt,
                reasonCode, message, now);
        return ApiException.unprocessable(message, reasonCode);
    }

    /**
     * 许可签发冲突同样在独立审计事务留痕（含原因码），再返回 409 异常触发业务回滚。
     */
    private ApiException conflictAudited(String requestId, Instant now, String deviceId,
                                         String reasonCode, String message) {
        rejectionLogRepository.append(requestId, "PERMIT_CREATE", null, deviceId, null, null, null,
                reasonCode, message, now);
        return ApiException.conflictWithReason(message, reasonCode);
    }

    // ---------- 幂等占位/回填（按结果类型具体化，语义同观测域） ----------

    private PermitOutcome replayPermit(String requestId, String fingerprint) {
        RequestLogEntry entry = replayEntry(requestId, fingerprint);
        return entry == null ? null
                : new PermitOutcome(entry.responseStatus(), readBody(entry.responseBody(), PermitResponse.class));
    }

    private SubmitOutcome replaySubmit(String requestId, String fingerprint) {
        RequestLogEntry entry = replayEntry(requestId, fingerprint);
        return entry == null ? null
                : new SubmitOutcome(entry.responseStatus(),
                        readBody(entry.responseBody(), PermitObservationResponse.class));
    }

    private BatchOutcome replayBatch(String requestId, String fingerprint) {
        RequestLogEntry entry = replayEntry(requestId, fingerprint);
        return entry == null ? null
                : new BatchOutcome(entry.responseStatus(), readBody(entry.responseBody(), BatchMergeResponse.class));
    }

    private ClusterOutcome replayCluster(String requestId, String fingerprint) {
        RequestLogEntry entry = replayEntry(requestId, fingerprint);
        return entry == null ? null
                : new ClusterOutcome(entry.responseStatus(),
                        readBody(entry.responseBody(), ClusterResolutionResponse.class));
    }

    private PermitOutcome occupyPermit(String requestId, String fingerprint, String operation) {
        if (insertPlaceholder(requestId, fingerprint, operation)) {
            return null;
        }
        return replayPermit(requestId, fingerprint);
    }

    private SubmitOutcome occupySubmit(String requestId, String fingerprint, String operation) {
        if (insertPlaceholder(requestId, fingerprint, operation)) {
            return null;
        }
        return replaySubmit(requestId, fingerprint);
    }

    private BatchOutcome occupyBatch(String requestId, String fingerprint, String operation) {
        if (insertPlaceholder(requestId, fingerprint, operation)) {
            return null;
        }
        return replayBatch(requestId, fingerprint);
    }

    private ClusterOutcome occupyCluster(String requestId, String fingerprint, String operation) {
        if (insertPlaceholder(requestId, fingerprint, operation)) {
            return null;
        }
        return replayCluster(requestId, fingerprint);
    }

    /**
     * 同键同参返回已提交记录；同键异参 409；无记录返回 null。
     * 许可域按请求参数指纹比对（requestFingerprint 非空时优先），使重放短路不依赖当前许可命中结果。
     */
    private RequestLogEntry replayEntry(String requestId, String requestFingerprint) {
        Optional<RequestLogEntry> existing = requestLogRepository.find(requestId);
        if (existing.isEmpty()) {
            return null;
        }
        RequestLogEntry entry = existing.get();
        String stored = entry.requestFingerprint() != null ? entry.requestFingerprint() : entry.fingerprint();
        if (!stored.equals(requestFingerprint)) {
            throw ApiException.conflict("requestId reused with different parameters: " + requestId, null);
        }
        return entry;
    }

    /**
     * 占位写入：成功返回 true；主键冲突表示并发同键事务已提交，由调用方走重放路径。
     * 业务失败时占位随事务回滚，不占键。
     */
    private boolean insertPlaceholder(String requestId, String requestFingerprint, String operation) {
        try {
            requestLogRepository.insertPermitPlaceholder(requestId, requestFingerprint, operation);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private PermitOutcome completePermit(String requestId, HttpStatus status, PermitResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new PermitOutcome(status.value(), body);
    }

    private SubmitOutcome completeSubmit(String requestId, HttpStatus status, PermitObservationResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new SubmitOutcome(status.value(), body);
    }

    private BatchOutcome completeBatch(String requestId, HttpStatus status, BatchMergeResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new BatchOutcome(status.value(), body);
    }

    private ClusterOutcome completeCluster(String requestId, HttpStatus status, ClusterResolutionResponse body) {
        requestLogRepository.complete(requestId, status.value(), writeJson(body));
        return new ClusterOutcome(status.value(), body);
    }

    private <T> T readBody(String json, Class<T> bodyType) {
        try {
            return objectMapper.readValue(json, bodyType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize stored response", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response", e);
        }
    }

    /**
     * 观测业务唯一键：设备标识|采集时刻(UTC,ISO)|经度|纬度，坐标去尾零以保证稳定。
     */
    static String observationKey(String deviceId, BigDecimal lon, BigDecimal lat, Instant sampledAt) {
        return String.join("|", deviceId, sampledAt.toString(),
                lon.stripTrailingZeros().toPlainString(), lat.stripTrailingZeros().toPlainString());
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
     * 许可写操作结果。
     */
    public record PermitOutcome(int status, PermitResponse body) {
    }

    /**
     * 单条提交结果。
     */
    public record SubmitOutcome(int status, PermitObservationResponse body) {
    }

    /**
     * 批量合并结果。
     */
    public record BatchOutcome(int status, BatchMergeResponse body) {
    }

    /**
     * 冲突簇裁决结果。
     */
    public record ClusterOutcome(int status, ClusterResolutionResponse body) {
    }
}
