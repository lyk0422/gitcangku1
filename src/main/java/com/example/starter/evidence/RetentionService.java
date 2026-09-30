package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.DestructionApplyRequest;
import com.example.starter.evidence.dto.DestructionView;
import com.example.starter.evidence.dto.HoldChainView;
import com.example.starter.evidence.dto.HoldCreateRequest;
import com.example.starter.evidence.dto.HoldReleaseRequest;
import com.example.starter.evidence.dto.HoldView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 保留冻结与销毁申请核心服务。
 *
 * <p>并发正确性：销毁与创建证物级冻结先锁证物行（SELECT ... FOR UPDATE），
 * 创建案件级冻结时按 id 顺序锁案件下全部证物行——二者抢同一组行锁，
 * 因此不可能出现"冻结已生效而证物同时被销毁"。被拒销毁也落库（REJECTED），
 * 占用自身 requestId；冻结到期/解除不改写历史，重新销毁须使用新 requestId。
 *
 * <p>幂等：冻结、解除、销毁三类写操作均以各自 requestId 作为全局幂等键，
 * 同键同参重放首次结果（含首次 409），同键换对象/到期时间/原因返回 409。
 * 这里不写 command_log：requestId 唯一约束就在业务表上，重放直接回放业务首行。
 */
@Service
public class RetentionService {

    static final String OP_HOLD_PLACE = "HOLD_PLACE";
    static final String OP_HOLD_RELEASE = "HOLD_RELEASE";
    static final String OP_DESTRUCTION_APPLY = "DESTRUCTION_APPLY";

    private final EvidenceRepository evidenceRepository;
    private final RetentionHoldRepository holdRepository;
    private final DestructionRequestRepository destructionRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public RetentionService(EvidenceRepository evidenceRepository,
                            RetentionHoldRepository holdRepository,
                            DestructionRequestRepository destructionRepository,
                            ObjectMapper objectMapper,
                            EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.holdRepository = holdRepository;
        this.destructionRepository = destructionRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建案件级保留冻结。按 id 顺序锁案件下全部证物行后插入 ACTIVE 冻结，
     * 与同案件证物的销毁互斥；requestId 全局唯一，同键换案件/到期时间/原因返回 409。
     * 到期时刻须晚于当前 UTC 时刻（左闭右开窗口的合法起点）。
     */
    @Transactional
    public StoredResponse placeCaseHold(String actorId, String caseKey,
                                        HoldCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkHoldReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime nowUtc = clock.nowUtc();
        requireFutureExpiry(request.expiresUtc(), nowUtc);

        // 锁案件下全部证物行（id 顺序，避免死锁），与销毁/证物级冻结互斥。
        List<Evidence> evidences = evidenceRepository.findByCaseKeyForUpdate(caseKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等，重放先提交事务的首次结果。
        replay = checkHoldReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (evidences.isEmpty()) {
            throw ApiException.notFound("案件不存在或案件下无证物: " + caseKey);
        }
        if (evidences.stream().anyMatch(e -> e.status() == EvidenceStatus.DESTROYED)) {
            throw ApiException.conflict("案件存在已销毁证物，不能再建立案件级冻结: " + caseKey);
        }
        holdRepository.insert(request.requestId(), requestHash, HoldScope.CASE, caseKey, null,
                request.expiresUtc(), request.reason(), actorId, nowUtc, LocalDateTime.now());
        RetentionHold hold = holdRepository.findByRequestId(request.requestId()).orElseThrow();
        return new StoredResponse(201, toJson(toView(hold, nowUtc)));
    }

    /**
     * 创建单件证物保留冻结。锁证物行后插入 ACTIVE 冻结；requestId 同键改参返回 409。
     */
    @Transactional
    public StoredResponse placeEvidenceHold(String actorId, String evidenceKey,
                                            HoldCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkHoldReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime nowUtc = clock.nowUtc();
        requireFutureExpiry(request.expiresUtc(), nowUtc);

        Evidence evidence = lockEvidence(evidenceKey);
        // 持锁后复查幂等。
        replay = checkHoldReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (evidence.status() == EvidenceStatus.DESTROYED) {
            throw ApiException.conflict("证物已销毁，不能再建立冻结: " + evidenceKey);
        }
        holdRepository.insert(request.requestId(), requestHash, HoldScope.EVIDENCE,
                evidence.caseKey(), evidenceKey, request.expiresUtc(), request.reason(),
                actorId, nowUtc, LocalDateTime.now());
        RetentionHold hold = holdRepository.findByRequestId(request.requestId()).orElseThrow();
        return new StoredResponse(201, toJson(toView(hold, nowUtc)));
    }

    /**
     * 解除冻结：必须由不同于创建人的保管人签明非空原因。
     * 解除只影响后续销毁申请，不改写过去裁决；同解除 requestId 重放首次结果，换冻结/原因返回 409。
     */
    @Transactional
    public StoredResponse releaseHold(String actorId, Long holdId,
                                      HoldReleaseRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReleaseReplay(request.requestId(), requestHash, holdId);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime nowUtc = clock.nowUtc();

        RetentionHold hold = holdRepository.findActiveByIdForUpdate(holdId).orElse(null);
        if (hold == null) {
            // 持锁后未发现 ACTIVE 冻结：可能是同 requestId 并发/重复解除（重放首次 200），
            // 也可能是他请求已解除或冻结不存在（409）。
            return checkReleaseReplay(request.requestId(), requestHash, holdId)
                    .orElseThrow(() -> ApiException.conflict(
                            "冻结不存在、已解除或已处理: " + holdId));
        }
        // 持锁后复查解除幂等。
        replay = checkReleaseReplay(request.requestId(), requestHash, holdId);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (hold.createdBy().equals(actorId)) {
            throw ApiException.conflict("解除保管人必须不同于冻结创建人: " + actorId);
        }
        if (!holdRepository.release(holdId, request.requestId(), requestHash, actorId,
                request.reason(), nowUtc)) {
            throw ApiException.conflict("冻结已被并发解除: " + holdId);
        }
        RetentionHold released = holdRepository.findActiveByIdForUpdate(holdId)
                .orElseGet(() -> holdRepository.findByReleaseRequestId(request.requestId())
                        .orElseThrow());
        return new StoredResponse(200, toJson(toView(released, nowUtc)));
    }

    /**
     * 销毁申请：锁证物行后同时检查证物级与所属案件级的有效冻结。
     * 冻结窗口左闭右开 [created, expires)：当前 UTC 时刻恰等于到期时刻时不再阻断。
     * 无有效冻结则证物进入 DESTROYED（终态），裁决 APPROVED；否则裁决 REJECTED 并返回 409，
     * 证物状态保持不变。被拒记录同样占用 requestId，到期/解除后须用新 requestId 重新申请。
     */
    @Transactional
    public StoredResponse applyDestruction(String actorId, String evidenceKey,
                                           DestructionApplyRequest request, String requestHash) {
        Optional<StoredResponse> replay =
                checkDestructionReplay(request.requestId(), requestHash, evidenceKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime nowUtc = clock.nowUtc();

        Evidence evidence = lockEvidence(evidenceKey);
        // 持锁后复查幂等。
        replay = checkDestructionReplay(request.requestId(), requestHash, evidenceKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (evidence.status() == EvidenceStatus.DESTROYED) {
            throw ApiException.conflict("证物已销毁: " + evidenceKey);
        }
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人，不能申请销毁: " + actorId);
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待接收交接期间禁止销毁: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("借出未归还期间禁止销毁: " + evidenceKey);
        }

        Optional<RetentionHold> blocking =
                holdRepository.findBlockingHold(evidence.caseKey(), evidenceKey, nowUtc);
        if (blocking.isPresent()) {
            RetentionHold hold = blocking.get();
            destructionRepository.insert(request.requestId(), requestHash, evidenceKey,
                    evidence.caseKey(), request.reason(), actorId, DestructionStatus.REJECTED,
                    hold.id(), nowUtc, LocalDateTime.now());
            DestructionRequestRecord saved =
                    destructionRepository.findByRequestId(request.requestId()).orElseThrow();
            // 被有效冻结拒绝：409 且证物状态不变。
            return new StoredResponse(409, toJson(toDestructionView(saved)));
        }

        destructionRepository.insert(request.requestId(), requestHash, evidenceKey,
                evidence.caseKey(), request.reason(), actorId, DestructionStatus.APPROVED,
                null, nowUtc, LocalDateTime.now());
        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.DESTROYED, LocalDateTime.now());
        DestructionRequestRecord saved =
                destructionRepository.findByRequestId(request.requestId()).orElseThrow();
        return new StoredResponse(200, toJson(toDestructionView(saved)));
    }

    /**
     * 查询单件证物的证物级冻结（含已解除/已到期），effective 为查询时刻左闭右开判定。
     */
    @Transactional(readOnly = true)
    public List<HoldView> listEvidenceHolds(String evidenceKey) {
        if (evidenceRepository.findByKey(evidenceKey).isEmpty()) {
            throw ApiException.notFound("证物不存在: " + evidenceKey);
        }
        LocalDateTime nowUtc = clock.nowUtc();
        return holdRepository.findEvidenceHoldsByEvidenceKey(evidenceKey).stream()
                .map(hold -> toView(hold, nowUtc))
                .toList();
    }

    /**
     * 查询案件两级冻结链（案件级 + 全部证物级）及案件下全部销毁申请历史。
     */
    @Transactional(readOnly = true)
    public HoldChainView caseHoldChain(String caseKey) {
        LocalDateTime nowUtc = clock.nowUtc();
        List<HoldView> holds = holdRepository.findByCaseKey(caseKey).stream()
                .map(hold -> toView(hold, nowUtc))
                .toList();
        List<DestructionView> destructions = destructionRepository.findByCaseKey(caseKey).stream()
                .map(this::toDestructionView)
                .toList();
        return new HoldChainView(caseKey, holds, destructions);
    }

    /**
     * 查询单件证物的全部销毁申请历史（含过去被拒记录，裁决不可改写）。
     */
    @Transactional(readOnly = true)
    public List<DestructionView> listDestructions(String evidenceKey) {
        if (evidenceRepository.findByKey(evidenceKey).isEmpty()) {
            throw ApiException.notFound("证物不存在: " + evidenceKey);
        }
        return destructionRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toDestructionView)
                .toList();
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void requireFutureExpiry(java.time.LocalDateTime expiresUtc, LocalDateTime nowUtc) {
        if (!expiresUtc.isAfter(nowUtc)) {
            throw ApiException.badRequest("冻结到期时刻须晚于服务端当前 UTC 时刻");
        }
    }

    /**
     * 冻结创建重放：同 requestId 同参返回首次结果；同键换对象/到期时间/原因返回 409。
     */
    private Optional<StoredResponse> checkHoldReplay(String requestId, String requestHash) {
        return holdRepository.findByRequestId(requestId).map(hold -> {
            if (!hold.requestHash().equals(requestHash)) {
                throw ApiException.conflict("冻结 requestId 已被不同参数使用: " + requestId);
            }
            return new StoredResponse(201, toJson(toView(hold, clock.nowUtc())));
        });
    }

    /**
     * 解除重放：同解除 requestId 必须作用于同一冻结且同参，返回首次（解除后）结果；
     * 换冻结或原因返回 409。
     */
    private Optional<StoredResponse> checkReleaseReplay(String requestId, String requestHash,
                                                        Long holdId) {
        return holdRepository.findByReleaseRequestId(requestId).map(hold -> {
            if (!hold.id().equals(holdId) || !hold.releaseRequestHash().equals(requestHash)) {
                throw ApiException.conflict("解除 requestId 已被不同参数使用: " + requestId);
            }
            return new StoredResponse(200, toJson(toView(hold, clock.nowUtc())));
        });
    }

    /**
     * 销毁重放：同 requestId 同参返回首次裁决（可能是 200 或首次 409）；换证物/原因返回 409。
     */
    private Optional<StoredResponse> checkDestructionReplay(String requestId, String requestHash,
                                                            String evidenceKey) {
        return destructionRepository.findByRequestId(requestId).map(saved -> {
            if (!saved.evidenceKey().equals(evidenceKey) || !saved.requestHash().equals(requestHash)) {
                throw ApiException.conflict("销毁 requestId 已被不同参数使用: " + requestId);
            }
            int status = saved.status() == DestructionStatus.APPROVED ? 200 : 409;
            return new StoredResponse(status, toJson(toDestructionView(saved)));
        });
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private HoldView toView(RetentionHold hold, LocalDateTime nowUtc) {
        boolean effective = HoldWindow.isEffective(hold.status(), hold.createdUtc(),
                hold.expiresUtc(), nowUtc);
        return new HoldView(
                hold.id(),
                hold.requestId(),
                hold.scope(),
                hold.caseKey(),
                hold.evidenceKey(),
                hold.expiresUtc(),
                hold.reason(),
                hold.createdBy(),
                hold.createdUtc(),
                hold.status(),
                effective,
                hold.releasedBy(),
                hold.releaseReason(),
                hold.releasedUtc());
    }

    private DestructionView toDestructionView(DestructionRequestRecord saved) {
        return new DestructionView(
                saved.requestId(),
                saved.evidenceKey(),
                saved.caseKey(),
                saved.reason(),
                saved.requestedBy(),
                saved.status(),
                saved.blockingHoldId(),
                saved.requestedUtc());
    }
}
