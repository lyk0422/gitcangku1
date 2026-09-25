package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.BlockedHoldView;
import com.example.starter.evidence.dto.DestructionSubmitRequest;
import com.example.starter.evidence.dto.DestructionView;
import com.example.starter.evidence.dto.HoldBatchReleaseRequest;
import com.example.starter.evidence.dto.HoldCreateRequest;
import com.example.starter.evidence.dto.HoldReleaseItem;
import com.example.starter.evidence.dto.HoldView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 证物保全冻结与销毁申请双向门禁服务。
 *
 * <p>裁决规则：冻结、销毁申请、借出与解除均按提交顺序（证物行锁上的事务提交顺序）裁决。
 * 一切写操作在单事务内完成：先锁定涉及的全部证物行（按规范化顺序，避免死锁），
 * 再校验并落库，最后写幂等日志；失败抛异常整单回滚，不留下半成品。</p>
 */
@Service
public class RetentionService {

    static final String OP_HOLD_CREATE = "HOLD_CREATE";
    static final String OP_HOLD_BATCH_RELEASE = "HOLD_BATCH_RELEASE";
    static final String OP_DESTRUCTION_SUBMIT = "DESTRUCTION_SUBMIT";
    static final String OP_DESTRUCTION_COMPLETE = "DESTRUCTION_COMPLETE";

    private final EvidenceRepository evidenceRepository;
    private final RetentionHoldRepository holdRepository;
    private final DestructionRequestRepository destructionRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public RetentionService(EvidenceRepository evidenceRepository,
                            RetentionHoldRepository holdRepository,
                            DestructionRequestRepository destructionRepository,
                            CommandLogRepository commandLogRepository,
                            ObjectMapper objectMapper,
                            EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.holdRepository = holdRepository;
        this.destructionRepository = destructionRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 建立保全冻结。
     * 校验区间合法（左闭右开、生效不早于当前）、证物均存在、未被销毁；
     * 同一证物与既有有效冻结区间重叠则拒绝（422，稳定列出冲突 holdId）；
     * 创建成功后，与冻结区间相交的既有 PENDING 销毁申请转 HOLD_BLOCKED 并留不可变快照。
     */
    @Transactional
    public StoredResponse createHold(String actorId, HoldCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> evidenceKeys = normalize(request.evidenceKeys());
        LocalDateTime now = clock.nowUtc();
        validateRange(request.effectiveAt(), request.expireAt(), now);
        if (holdRepository.findByHoldId(request.holdId()).isPresent()) {
            throw ApiException.conflict("冻结已存在: " + request.holdId());
        }
        // 按规范化顺序锁定全部证物行：冻结/销毁/借出并发在此串行化，按提交顺序裁决。
        List<Evidence> evidence = lockAll(evidenceKeys);
        // 持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        for (Evidence e : evidence) {
            if (e.status() == EvidenceStatus.DESTROYED) {
                throw ApiException.unprocessable("证物已销毁，不能建立冻结: " + e.evidenceKey());
            }
        }
        TreeSet<String> overlapping = new TreeSet<>();
        for (String key : evidenceKeys) {
            for (RetentionHold h : holdRepository.findActiveOverlapping(
                    key, request.effectiveAt(), request.expireAt())) {
                overlapping.add(h.holdId());
            }
        }
        if (!overlapping.isEmpty()) {
            throw ApiException.unprocessable("证物存在时间重叠的有效冻结: " + String.join(",", overlapping));
        }

        String holdKey = fingerprint(request.caseKey(), evidenceKeys,
                request.effectiveAt(), request.expireAt(), request.reason(), 1);
        holdRepository.insert(request.holdId(), holdKey, request.caseKey(),
                request.effectiveAt(), request.expireAt(), request.reason(), actorId, now);
        RetentionHold hold = holdRepository.findByHoldId(request.holdId()).orElseThrow();
        for (int i = 0; i < evidenceKeys.size(); i++) {
            holdRepository.insertItem(hold.id(), evidenceKeys.get(i), i);
        }

        // 冻结开始后：相交的待审销毁申请立即（区间相交即视为命中）转 HOLD_BLOCKED。
        blockPendingRequests(evidenceKeys, request.effectiveAt(), request.expireAt(), now);

        HoldView view = toView(hold, evidenceKeys, now);
        return record(request.commandKey(), OP_HOLD_CREATE, actorId, requestHash, 201, view);
    }

    /**
     * 批量解除冻结：逐项校验请求方（必须为创建人）与期望版本（乐观锁），
     * 任一失败整批回滚；全部成功才提交，不产生部分解除。
     */
    @Transactional
    public StoredResponse batchReleaseHolds(String actorId, HoldBatchReleaseRequest request,
                                            String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 按 holdId 规范化排序去重，稳定加锁顺序；重复 holdId 本身视为参数冲突。
        TreeSet<String> seen = new TreeSet<>();
        for (HoldReleaseItem item : request.releases()) {
            if (!seen.add(item.holdId())) {
                throw ApiException.badRequest("批量解除中存在重复冻结: " + item.holdId());
            }
        }
        LocalDateTime now = clock.nowUtc();
        List<HoldView> released = new ArrayList<>();
        for (HoldReleaseItem item : request.releases()) {
            RetentionHold hold = holdRepository.findByHoldIdForUpdate(item.holdId())
                    .orElseThrow(() -> ApiException.notFound("冻结不存在: " + item.holdId()));
            // 持锁后复查幂等（首批加锁后可能已有并发同键事务提交）。
            Optional<StoredResponse> afterLock = checkReplay(request.commandKey(), requestHash);
            if (afterLock.isPresent()) {
                return afterLock.get();
            }
            if (!hold.createdBy().equals(actorId)) {
                throw ApiException.conflict("仅冻结创建请求方可解除: " + item.holdId());
            }
            if (hold.status() != HoldStatus.ACTIVE) {
                throw ApiException.conflict("冻结已解除，不能重复解除: " + item.holdId());
            }
            if (hold.version() != item.expectedVersion()) {
                throw ApiException.conflict("冻结版本不匹配: " + item.holdId()
                        + " expected=" + item.expectedVersion() + " actual=" + hold.version());
            }
            if (!holdRepository.release(item.holdId(), item.expectedVersion(), actorId, now)) {
                throw ApiException.conflict("冻结已被并发解除或版本变更: " + item.holdId());
            }
            RetentionHold updated = holdRepository.findByHoldId(item.holdId()).orElseThrow();
            released.add(toView(updated, holdRepository.findItems(hold.id()), now));
        }
        return record(request.commandKey(), OP_HOLD_BATCH_RELEASE, actorId, requestHash,
                200, Map.of("released", released));
    }

    /**
     * 提交销毁申请：先校验全部证物的最终状态与封签，再校验全部有效冻结。
     * 任一证物命中有效冻结返回 422 并稳定列出 holdId，不生成任何申请行（无部分申请）。
     */
    @Transactional
    public StoredResponse submitDestruction(String actorId, DestructionSubmitRequest request,
                                            String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> evidenceKeys = normalize(request.evidenceKeys());
        LocalDateTime now = clock.nowUtc();
        if (destructionRepository.findByRequestKey(request.requestKey()).isPresent()) {
            throw ApiException.conflict("销毁申请已存在: " + request.requestKey());
        }
        List<Evidence> evidence = lockAll(evidenceKeys);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 1) 最终证物状态与封签门禁。
        for (Evidence e : evidence) {
            if (e.status() == EvidenceStatus.DESTROYED) {
                throw ApiException.unprocessable("证物已完成销毁，不能重复申请: " + e.evidenceKey());
            }
            if (e.status() == EvidenceStatus.SEAL_BROKEN) {
                throw ApiException.unprocessable("封签异常证物禁止销毁: " + e.evidenceKey());
            }
            if (e.status() == EvidenceStatus.BORROWED) {
                throw ApiException.conflict("借出未归还证物禁止提交销毁: " + e.evidenceKey());
            }
            if (e.status() == EvidenceStatus.TRANSFER_PENDING) {
                throw ApiException.conflict("待接收交接期间禁止提交销毁: " + e.evidenceKey());
            }
            if (!e.custodianId().equals(actorId)) {
                throw ApiException.conflict("仅当前保管人可提交销毁: " + e.evidenceKey());
            }
        }
        // 2) 有效冻结门禁：任一命中即 422，稳定（字典序）列出 holdId，不写申请。
        TreeSet<String> blocking = new TreeSet<>();
        for (String key : evidenceKeys) {
            for (RetentionHold h : holdRepository.findEffectiveAt(key, now)) {
                blocking.add(h.holdId());
            }
        }
        if (!blocking.isEmpty()) {
            throw ApiException.unprocessable("销毁被有效冻结阻断: " + String.join(",", blocking));
        }

        destructionRepository.insert(request.requestKey(), actorId, now);
        DestructionRequest created = destructionRepository.findByRequestKey(request.requestKey())
                .orElseThrow();
        for (int i = 0; i < evidenceKeys.size(); i++) {
            destructionRepository.insertItem(created.id(), evidenceKeys.get(i), i);
        }
        return record(request.commandKey(), OP_DESTRUCTION_SUBMIT, actorId, requestHash,
                201, toView(created, evidenceKeys, List.of()));
    }

    /**
     * 完成销毁：仅申请提交方，申请须仍为 PENDING（被阻断者须重新提交，解除冻结不自动批准）；
     * 完成时再次校验全部证物仍由本人保管、封签完好且无有效冻结，全部证物置 DESTROYED。
     */
    @Transactional
    public StoredResponse completeDestruction(String actorId, String requestKey,
                                              com.example.starter.evidence.dto.CommandRequest request,
                                              String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        DestructionRequest destruction = destructionRepository.findByRequestKey(requestKey)
                .orElseThrow(() -> ApiException.notFound("销毁申请不存在: " + requestKey));
        if (!destruction.requestedBy().equals(actorId)) {
            throw ApiException.conflict("仅销毁申请提交方可完成销毁: " + requestKey);
        }
        if (destruction.status() == DestructionStatus.HOLD_BLOCKED) {
            throw ApiException.conflict("申请已被冻结阻断，冻结解除后不自动批准，须重新提交: " + requestKey);
        }
        if (destruction.status() == DestructionStatus.DESTROYED) {
            throw ApiException.conflict("销毁申请已完成，不能重复销毁: " + requestKey);
        }
        List<String> evidenceKeys = destructionRepository.findItems(destruction.id());
        LocalDateTime now = clock.nowUtc();
        List<Evidence> evidence = lockAll(evidenceKeys);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        for (Evidence e : evidence) {
            if (e.status() == EvidenceStatus.DESTROYED) {
                throw ApiException.unprocessable("证物已完成销毁: " + e.evidenceKey());
            }
            if (e.status() != EvidenceStatus.SEALED || !e.custodianId().equals(actorId)) {
                throw ApiException.conflict("证物状态已变更，不能完成销毁: " + e.evidenceKey());
            }
        }
        TreeSet<String> blocking = new TreeSet<>();
        Map<String, RetentionHold> hits = new LinkedHashMap<>();
        for (String key : evidenceKeys) {
            for (RetentionHold h : holdRepository.findEffectiveAt(key, now)) {
                if (blocking.add(h.holdId())) {
                    hits.put(h.holdId(), h);
                }
            }
        }
        if (!blocking.isEmpty()) {
            // 冻结开始后待审申请须转为 HOLD_BLOCKED 并留不可变原因（含未来生效冻结到期后首次操作的情形）。
            // 该状态转换必须提交，因此返回 422 结果而非抛异常回滚；不写幂等日志，失败不占键。
            List<RetentionHold> ordered = blocking.stream().map(hits::get).toList();
            persistBlocked(destruction, ordered, now);
            DestructionRequest blocked = destructionRepository.findByRequestKey(requestKey)
                    .orElseThrow();
            List<BlockedHoldView> blockedViews = toBlockedViews(ordered);
            return new StoredResponse(422, toJson(toView(blocked, evidenceKeys, blockedViews)));
        }
        if (!destructionRepository.markCompleted(destruction.id(), now)) {
            throw ApiException.conflict("销毁申请已被并发处理: " + requestKey);
        }
        for (String key : evidenceKeys) {
            evidenceRepository.updateStatus(key, EvidenceStatus.DESTROYED, now);
        }
        DestructionRequest completed = destructionRepository.findByRequestKey(requestKey).orElseThrow();
        return record(request.commandKey(), OP_DESTRUCTION_COMPLETE, actorId, requestHash,
                200, toView(completed, evidenceKeys, List.of()));
    }

    /**
     * 查询一件证物在当前 UTC 时刻的有效冻结（ACTIVE 且处于区间内）。
     */
    @Transactional(readOnly = true)
    public List<HoldView> listEffectiveHolds(String evidenceKey) {
        requireEvidenceExists(evidenceKey);
        LocalDateTime now = clock.nowUtc();
        return holdRepository.findEffectiveAt(evidenceKey, now).stream()
                .map(h -> toView(h, holdRepository.findItems(h.id()), now))
                .toList();
    }

    /**
     * 查询证物历史上的全部冻结（含未生效、已过期、已解除），按创建顺序。
     */
    @Transactional(readOnly = true)
    public List<HoldView> listHoldHistory(String evidenceKey) {
        requireEvidenceExists(evidenceKey);
        LocalDateTime now = clock.nowUtc();
        return holdRepository.findAllByEvidenceKey(evidenceKey).stream()
                .map(h -> toView(h, holdRepository.findItems(h.id()), now))
                .toList();
    }

    /**
     * 查询销毁申请详情，含提交集合快照与不可变阻断冻结快照。
     */
    @Transactional(readOnly = true)
    public DestructionView getDestruction(String requestKey) {
        DestructionRequest destruction = destructionRepository.findByRequestKey(requestKey)
                .orElseThrow(() -> ApiException.notFound("销毁申请不存在: " + requestKey));
        List<String> items = destructionRepository.findItems(destruction.id());
        List<BlockedHoldView> blocked = destructionRepository.findBlockSnapshots(destruction.id())
                .stream()
                .map(s -> new BlockedHoldView(s.holdId(), s.holdVersion(), s.caseKey(),
                        s.effectiveAt(), s.expireAt(), s.reason()))
                .toList();
        return toView(destruction, items, blocked);
    }

    /**
     * 查询一件证物关联的销毁阻断历史（含 PENDING/HOLD_BLOCKED/DESTROYED），按提交顺序。
     */
    @Transactional(readOnly = true)
    public List<DestructionView> listDestructionHistory(String evidenceKey) {
        requireEvidenceExists(evidenceKey);
        return destructionRepository.findAllByEvidenceKey(evidenceKey).stream()
                .map(r -> {
                    List<String> items = destructionRepository.findItems(r.id());
                    List<BlockedHoldView> blocked = destructionRepository.findBlockSnapshots(r.id())
                            .stream()
                            .map(s -> new BlockedHoldView(s.holdId(), s.holdVersion(), s.caseKey(),
                                    s.effectiveAt(), s.expireAt(), s.reason()))
                            .toList();
                    return toView(r, items, blocked);
                })
                .toList();
    }

    /**
     * 将与待审销毁申请相交、且在冻结区间内有效的申请批量转 HOLD_BLOCKED，写不可变快照。
     * 调用前已锁定冻结涉及的全部证物行，故相交申请不会被并发完成。
     */
    private void blockPendingRequests(List<String> holdEvidenceKeys,
                                      LocalDateTime effectiveAt, LocalDateTime expireAt,
                                      LocalDateTime now) {
        List<DestructionRequest> pending =
                destructionRepository.findPendingByEvidenceKeysForUpdate(holdEvidenceKeys);
        for (DestructionRequest request : pending) {
            List<String> requestItems = destructionRepository.findItems(request.id());
            // 申请被阻断当且仅当：申请集合中至少一件证物属于本冻结集合，且冻结此刻已生效。
            boolean intersects = requestItems.stream().anyMatch(holdEvidenceKeys::contains);
            boolean effectiveNow = !now.isBefore(effectiveAt) && now.isBefore(expireAt);
            if (!intersects || !effectiveNow) {
                continue;
            }
            // 该申请此刻命中的全部有效冻结（可能不止本冻结），统一写入不可变快照。
            TreeSet<String> holdIds = new TreeSet<>();
            List<RetentionHold> ordered = new ArrayList<>();
            for (String key : requestItems) {
                for (RetentionHold h : holdRepository.findEffectiveAt(key, now)) {
                    if (holdIds.add(h.holdId())) {
                        ordered.add(h);
                    }
                }
            }
            ordered.sort(java.util.Comparator.comparing(RetentionHold::holdId));
            persistBlocked(request, ordered, now);
        }
    }

    /**
     * 将一笔待审申请持久化为 HOLD_BLOCKED：条件更新状态与不可变原因，并按 holdId 稳定顺序写快照。
     * 调用方须保证申请行已锁定且命中冻结非空。
     */
    private void persistBlocked(DestructionRequest request, List<RetentionHold> orderedHolds,
                                LocalDateTime now) {
        List<BlockedHoldView> blockedViews = toBlockedViews(orderedHolds);
        String reasonJson = immutableReason(request.requestKey(), blockedViews);
        if (!destructionRepository.markBlocked(request.id(), reasonJson, now)) {
            throw ApiException.conflict("待审销毁申请已被并发处理: " + request.requestKey());
        }
        for (int i = 0; i < orderedHolds.size(); i++) {
            RetentionHold h = orderedHolds.get(i);
            destructionRepository.insertBlockSnapshot(request.id(),
                    new DestructionRequestRepository.DestructionBlockSnapshotView(
                            h.holdId(), h.version(), h.caseKey(),
                            h.effectiveAt(), h.expireAt(), h.reason()), i);
        }
    }

    /**
     * 按 holdId 稳定顺序构造阻断冻结视图。
     */
    private List<BlockedHoldView> toBlockedViews(List<RetentionHold> orderedHolds) {
        return orderedHolds.stream()
                .map(h -> new BlockedHoldView(h.holdId(), h.version(), h.caseKey(),
                        h.effectiveAt(), h.expireAt(), h.reason()))
                .toList();
    }

    /**
     * 构造不可变阻断原因（写入 blocked_reason），包含稳定 holdId 列表。
     */
    private String immutableReason(String requestKey, List<BlockedHoldView> blockedHolds) {
        try {
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("code", "HOLD_BLOCKED");
            reason.put("requestKey", requestKey);
            reason.put("holdIds", blockedHolds.stream().map(BlockedHoldView::holdId).toList());
            return objectMapper.writeValueAsString(reason);
        } catch (Exception e) {
            throw new IllegalStateException("阻断原因序列化失败", e);
        }
    }

    private void validateRange(LocalDateTime effectiveAt, LocalDateTime expireAt, LocalDateTime now) {
        if (!expireAt.isAfter(effectiveAt)) {
            throw ApiException.badRequest("失效时刻必须晚于生效时刻（左闭右开区间）");
        }
        if (effectiveAt.isBefore(now)) {
            throw ApiException.badRequest("生效时刻不得早于当前时刻，禁止补建覆盖过去的冻结");
        }
    }

    /**
     * 集合规范化：去重并按字典序排序。
     */
    private List<String> normalize(List<String> keys) {
        return List.copyOf(new TreeSet<>(keys));
    }

    /**
     * 按规范化顺序锁定全部证物行；任一不存在返回 404。
     */
    private List<Evidence> lockAll(List<String> evidenceKeys) {
        List<Evidence> evidence = new ArrayList<>();
        for (String key : evidenceKeys) {
            evidence.add(evidenceRepository.findByKeyForUpdate(key)
                    .orElseThrow(() -> ApiException.notFound("证物不存在: " + key)));
        }
        return evidence;
    }

    private void requireEvidenceExists(String evidenceKey) {
        if (evidenceRepository.findByKey(evidenceKey).isEmpty()) {
            throw ApiException.notFound("证物不存在: " + evidenceKey);
        }
    }

    /**
     * holdKey 指纹：案件号 + 规范化证物 + UTC 区间 + 原因 + 版本。
     */
    private String fingerprint(String caseKey, List<String> evidenceKeys,
                               LocalDateTime effectiveAt, LocalDateTime expireAt,
                               String reason, int version) {
        try {
            String canonical = String.join("\n",
                    caseKey,
                    String.join(",", evidenceKeys),
                    effectiveAt.toString(),
                    expireAt.toString(),
                    reason,
                    Integer.toString(version));
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
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

    private HoldView toView(RetentionHold hold, List<String> items, LocalDateTime now) {
        return new HoldView(hold.holdId(), hold.caseKey(), hold.reason(),
                hold.effectiveAt(), hold.expireAt(), hold.version(), hold.status(),
                hold.createdBy(), hold.createdAt(), hold.releasedBy(), hold.releasedAt(),
                List.copyOf(items), hold.activeAt(now));
    }

    private DestructionView toView(DestructionRequest request, List<String> items,
                                   List<BlockedHoldView> blockedHolds) {
        return new DestructionView(request.requestKey(), request.status(), request.requestedBy(),
                List.copyOf(items), List.copyOf(blockedHolds),
                request.createdAt(), request.blockedAt(), request.completedAt());
    }
}
