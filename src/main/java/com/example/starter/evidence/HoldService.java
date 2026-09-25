package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.error.HoldBlockedException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.DestructionRequestView;
import com.example.starter.evidence.dto.DestructionSubmitRequest;
import com.example.starter.evidence.dto.HoldCreateRequest;
import com.example.starter.evidence.dto.HoldReleaseRequest;
import com.example.starter.evidence.dto.HoldView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 证物保全冻结与销毁申请核心服务。与交接/借出共用证物行锁：
 * 冻结创建、销毁申请、解除均先按字典序锁定涉及证物行（SELECT ... FOR UPDATE），
 * 保证并发命令按事务提交顺序裁决，不留下半成品状态。
 * 冻结生效后待审销毁申请在同一事务内转为 HOLD_BLOCKED 并写入不可变原因；
 * 冻结结束或解除后不自动批准，须重新提交申请。
 */
@Service
public class HoldService {

    static final String OP_HOLD_CREATE = "HOLD_CREATE";
    static final String OP_HOLD_RELEASE = "HOLD_RELEASE";
    static final String OP_DESTRUCTION_SUBMIT = "DESTRUCTION_SUBMIT";
    static final String OP_DESTRUCTION_COMPLETE = "DESTRUCTION_COMPLETE";

    private final EvidenceRepository evidenceRepository;
    private final RetentionHoldRepository holdRepository;
    private final DestructionRequestRepository destructionRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public HoldService(EvidenceRepository evidenceRepository,
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
     * 创建保全冻结：证物集合已规范化排序；生效区间为 UTC 左闭右开，不得覆盖过去；
     * 同一证物存在重叠的有效（ACTIVE）冻结时拒绝（409）。创建成功后扫描待审销毁申请，
     * 命中本冻结及其他有效冻结的申请转为 HOLD_BLOCKED。
     */
    @Transactional
    public StoredResponse createHold(String actorId, HoldCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.holdKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!request.effectiveTo().isAfter(request.effectiveFrom())) {
            throw ApiException.badRequest("生效终点须晚于生效起点");
        }
        if (request.effectiveFrom().isBefore(nowUtc)) {
            throw ApiException.badRequest("不能补建覆盖过去的冻结: 生效起点早于当前时刻");
        }
        List<Evidence> locked = lockEvidenceInOrder(request.evidenceKeys());
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.holdKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        for (Evidence evidence : locked) {
            if (evidence.status() == EvidenceStatus.DESTROYED) {
                throw ApiException.unprocessable("证物已销毁，禁止建立冻结: " + evidence.evidenceKey());
            }
        }
        for (String evidenceKey : request.evidenceKeys()) {
            for (RetentionHold existing : holdRepository.findActiveByEvidenceKey(evidenceKey)) {
                boolean overlap = existing.effectiveFrom().isBefore(request.effectiveTo())
                        && request.effectiveFrom().isBefore(existing.effectiveTo());
                if (overlap) {
                    throw ApiException.conflict("同一证物存在重叠有效冻结: " + evidenceKey
                            + " 与 " + existing.holdKey());
                }
            }
        }
        holdRepository.insert(request.holdKey(), request.caseKey(), request.evidenceKeys(),
                request.effectiveFrom(), request.effectiveTo(), request.reason(),
                actorId, LocalDateTime.now());
        sweepHoldBlocks(nowUtc);
        RetentionHold hold = holdRepository.findByKey(request.holdKey()).orElseThrow();
        return record(request.holdKey(), OP_HOLD_CREATE, actorId, requestHash, 201, toView(hold));
    }

    /**
     * 批量解除冻结：先校验请求方（须为各冻结创建人）与冻结版本，任一失败整批回滚；
     * 全部校验通过后一次性解除。解除后冻结不再阻断销毁，但被阻断的申请不自动恢复。
     */
    @Transactional
    public StoredResponse releaseHolds(String actorId, HoldReleaseRequest request,
                                       String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> holdKeys = request.releases().stream()
                .map(HoldReleaseRequest.HoldReleaseItem::holdKey).sorted().toList();
        if (new LinkedHashSet<>(holdKeys).size() != holdKeys.size()) {
            throw ApiException.badRequest("同一批次内冻结键重复");
        }
        List<RetentionHold> holds = new ArrayList<>();
        for (String holdKey : holdKeys) {
            holds.add(holdRepository.findByKeyForUpdate(holdKey)
                    .orElseThrow(() -> ApiException.notFound("冻结不存在: " + holdKey)));
        }
        // 并发下本事务可能在冻结行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        for (RetentionHold hold : holds) {
            int expectedVersion = request.releases().stream()
                    .filter(item -> item.holdKey().equals(hold.holdKey()))
                    .findFirst().orElseThrow().version();
            if (!hold.createdBy().equals(actorId)) {
                throw ApiException.conflict("请求方不是冻结创建人: " + actorId
                        + " 无权解除 " + hold.holdKey());
            }
            if (hold.version() != expectedVersion) {
                throw ApiException.conflict("冻结版本不匹配: " + hold.holdKey()
                        + " 期望 " + expectedVersion + " 实际 " + hold.version());
            }
            if (hold.status() != HoldStatus.ACTIVE) {
                throw ApiException.conflict("冻结已解除: " + hold.holdKey());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (RetentionHold hold : holds) {
            if (!holdRepository.release(hold.id(), hold.version(), actorId, now)) {
                throw ApiException.conflict("冻结已被并发解除: " + hold.holdKey());
            }
        }
        List<HoldView> views = holdKeys.stream()
                .map(key -> toView(holdRepository.findByKey(key).orElseThrow()))
                .toList();
        return record(request.commandKey(), OP_HOLD_RELEASE, actorId, requestHash, 200, views);
    }

    /**
     * 提交销毁申请：先校验全部证物的最终状态（须 SEALED）、封签（非 SEAL_BROKEN）
     * 与所有有效冻结；任一证物命中冻结返回 422 并稳定列出 holdKey，
     * 不生成部分销毁申请；其他校验失败同样整批不落库。
     */
    @Transactional
    public StoredResponse submitDestruction(String actorId, DestructionSubmitRequest request,
                                            String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.requestKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime nowUtc = clock.nowUtc();
        sweepHoldBlocks(nowUtc);
        List<Evidence> locked = lockEvidenceInOrder(request.evidenceKeys());
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.requestKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        for (Evidence evidence : locked) {
            requireDestroyable(evidence);
        }
        Set<String> blocking = effectiveHoldKeys(request.evidenceKeys(), nowUtc);
        if (!blocking.isEmpty()) {
            List<String> holdKeys = List.copyOf(blocking);
            throw new HoldBlockedException(
                    "销毁申请命中有效冻结: " + String.join(",", holdKeys), holdKeys);
        }
        destructionRepository.insert(request.requestKey(), request.evidenceKeys(), actorId,
                request.reason(), LocalDateTime.now());
        DestructionRequest created = destructionRepository.findByKey(request.requestKey())
                .orElseThrow();
        return record(request.requestKey(), OP_DESTRUCTION_SUBMIT, actorId, requestHash,
                201, toView(created));
    }

    /**
     * 完成销毁：仅申请操作人，申请须仍为 PENDING；完成前复核证物状态与有效冻结。
     * 成功后申请转为 COMPLETED，涉及证物进入 DESTROYED（终态），保管链只读不可改写。
     */
    @Transactional
    public StoredResponse completeDestruction(String actorId, String requestKey,
                                              CommandRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime nowUtc = clock.nowUtc();
        sweepHoldBlocks(nowUtc);
        DestructionRequest destruction = destructionRepository.findByKeyForUpdate(requestKey)
                .orElseThrow(() -> ApiException.notFound("销毁申请不存在: " + requestKey));
        // 并发下本事务可能在申请行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!destruction.requestedBy().equals(actorId)) {
            throw ApiException.conflict("仅申请操作人可完成销毁: " + actorId);
        }
        if (destruction.status() == DestructionStatus.HOLD_BLOCKED) {
            throw ApiException.conflict("销毁申请已被冻结阻断，须重新提交: " + requestKey);
        }
        if (destruction.status() == DestructionStatus.COMPLETED) {
            throw ApiException.conflict("销毁申请已完成: " + requestKey);
        }
        List<Evidence> locked = lockEvidenceInOrder(destruction.evidenceKeys());
        for (Evidence evidence : locked) {
            requireDestroyable(evidence);
        }
        Set<String> blocking = effectiveHoldKeys(destruction.evidenceKeys(), nowUtc);
        if (!blocking.isEmpty()) {
            List<String> holdKeys = List.copyOf(blocking);
            throw new HoldBlockedException(
                    "销毁申请命中有效冻结: " + String.join(",", holdKeys), holdKeys);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!destructionRepository.markCompleted(destruction.id(), now)) {
            throw ApiException.conflict("销毁申请已被并发处理: " + requestKey);
        }
        for (Evidence evidence : locked) {
            evidenceRepository.updateStatus(evidence.evidenceKey(), EvidenceStatus.DESTROYED, now);
        }
        DestructionRequest completed = destructionRepository.findByKey(requestKey).orElseThrow();
        return record(request.commandKey(), OP_DESTRUCTION_COMPLETE, actorId, requestHash,
                200, toView(completed));
    }

    /**
     * 查询指定证物当前时刻的有效冻结（ACTIVE 且区间覆盖当前 UTC 时刻）。
     */
    @Transactional(readOnly = true)
    public List<HoldView> listEffectiveHolds(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        return holdRepository.findEffectiveByEvidenceKey(evidenceKey, clock.nowUtc())
                .stream().map(this::toView).toList();
    }

    /**
     * 查询指定证物的全部冻结历史快照（含已解除，按创建顺序；历史不可改写）。
     */
    @Transactional(readOnly = true)
    public List<HoldView> listHoldHistory(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        return holdRepository.findHistoryByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
    }

    /**
     * 查询指定证物的全部销毁申请（含阻断记录与冻结快照）。
     * 查询前在同一事务内扫描一次：冻结已生效的待审申请转为 HOLD_BLOCKED。
     */
    @Transactional
    public List<DestructionRequestView> listDestructionRequests(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        sweepHoldBlocks(clock.nowUtc());
        return destructionRepository.findAll().stream()
                .filter(request -> request.evidenceKeys().contains(evidenceKey))
                .map(this::toView)
                .toList();
    }

    /**
     * 阻断扫描：对所有待审申请，若其任一证物当前命中有效冻结，
     * 则转为 HOLD_BLOCKED 并写入不可变原因与冻结键快照（仅首次阻断生效）。
     */
    private void sweepHoldBlocks(LocalDateTime nowUtc) {
        for (DestructionRequest pending : destructionRepository.findPending()) {
            Set<String> blocking = effectiveHoldKeys(pending.evidenceKeys(), nowUtc);
            if (!blocking.isEmpty()) {
                List<String> holdKeys = List.copyOf(blocking);
                destructionRepository.markBlocked(pending.id(),
                        "冻结生效，销毁申请阻断: " + String.join(",", holdKeys),
                        holdKeys, LocalDateTime.now());
            }
        }
    }

    /**
     * 收集覆盖任一给定证物、当前时刻有效的冻结键（去重、字典序排序，保证稳定输出）。
     */
    private Set<String> effectiveHoldKeys(List<String> evidenceKeys, LocalDateTime nowUtc) {
        Set<String> keys = new TreeSet<>();
        for (String evidenceKey : evidenceKeys) {
            for (RetentionHold hold : holdRepository.findEffectiveByEvidenceKey(evidenceKey, nowUtc)) {
                keys.add(hold.holdKey());
            }
        }
        return keys;
    }

    private void requireDestroyable(Evidence evidence) {
        if (evidence.status() == EvidenceStatus.DESTROYED) {
            throw ApiException.unprocessable("证物已销毁: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止销毁申请: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("借出期间禁止销毁申请: " + evidence.evidenceKey());
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待接收交接期间禁止销毁申请: " + evidence.evidenceKey());
        }
    }

    private List<Evidence> lockEvidenceInOrder(List<String> evidenceKeys) {
        List<Evidence> locked = new ArrayList<>();
        for (String evidenceKey : evidenceKeys.stream().sorted().toList()) {
            locked.add(evidenceRepository.findByKeyForUpdate(evidenceKey)
                    .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey)));
        }
        return locked;
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

    private HoldView toView(RetentionHold hold) {
        LocalDateTime nowUtc = clock.nowUtc();
        boolean effective = hold.status() == HoldStatus.ACTIVE
                && !hold.effectiveFrom().isAfter(nowUtc)
                && hold.effectiveTo().isAfter(nowUtc);
        return new HoldView(hold.holdKey(), hold.caseKey(), hold.evidenceKeys(),
                hold.effectiveFrom(), hold.effectiveTo(), hold.reason(), hold.version(),
                hold.status(), effective, hold.createdBy(), hold.createdAt(),
                hold.releasedBy(), hold.releasedAt());
    }

    private DestructionRequestView toView(DestructionRequest request) {
        return new DestructionRequestView(request.requestKey(), request.evidenceKeys(),
                request.requestedBy(), request.reason(), request.status(), request.blockReason(),
                request.blockedHoldKeys(), request.createdAt(), request.decidedAt());
    }
}
