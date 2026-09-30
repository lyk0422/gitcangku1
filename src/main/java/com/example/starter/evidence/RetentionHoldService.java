package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseHoldsView;
import com.example.starter.evidence.dto.DestructionRequest;
import com.example.starter.evidence.dto.DestructionView;
import com.example.starter.evidence.dto.EvidenceHoldsView;
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
 * 保留冻结与销毁申请核心服务。全部写操作在单事务内完成：
 * 冻结创建先锁证物行（案件级锁案件下全部证物行），销毁先锁证物行再检查两级有效冻结，
 * 因此冻结与销毁并发时按证物行锁串行提交，不会出现冻结已生效而证物同时被销毁。
 * 冻结窗口为 UTC 左闭右开 [createdAt, expireAt)，恰好到期不再阻断新申请；
 * 被拒销毁以 REJECTED 记录提交保留（接口返回 409），到期与解除均不改写历史。
 */
@Service
public class RetentionHoldService {

    static final String OP_HOLD_CASE = "HOLD_CASE";
    static final String OP_HOLD_EVIDENCE = "HOLD_EVIDENCE";
    static final String OP_HOLD_RELEASE = "HOLD_RELEASE";
    static final String OP_DESTROY = "DESTROY";

    private final EvidenceRepository evidenceRepository;
    private final RetentionHoldRepository holdRepository;
    private final DestructionRecordRepository destructionRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public RetentionHoldService(EvidenceRepository evidenceRepository,
                                RetentionHoldRepository holdRepository,
                                DestructionRecordRepository destructionRepository,
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
     * 创建案件级冻结：锁定案件下全部证物行后写入冻结；案件下没有证物视为案件不存在。
     */
    @Transactional
    public StoredResponse createCaseHold(String actorId, String caseKey,
                                         HoldCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<Evidence> evidences = evidenceRepository.findByCaseKeyForUpdate(caseKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (evidences.isEmpty()) {
            throw ApiException.notFound("案件不存在或案件下无证物: " + caseKey);
        }
        Evidence destroyed = evidences.stream()
                .filter(e -> e.status() == EvidenceStatus.DESTROYED)
                .findFirst().orElse(null);
        if (destroyed != null) {
            // 与销毁并发且销毁先提交时，冻结不得生效，避免“冻结已生效而证物已销毁”。
            throw ApiException.conflict("案件存在已销毁证物，不能再创建案件级冻结: "
                    + destroyed.evidenceKey());
        }
        LocalDateTime nowUtc = clock.nowUtc();
        validateExpireAt(request.expireAt(), nowUtc);
        if (holdRepository.findByHoldKey(request.holdKey()).isPresent()) {
            throw ApiException.conflict("冻结键已存在: " + request.holdKey());
        }
        holdRepository.insert(request.holdKey(), HoldScope.CASE, caseKey, null,
                actorId, request.reason(), request.expireAt(), nowUtc);
        RetentionHold hold = holdRepository.findByHoldKey(request.holdKey()).orElseThrow();
        return record(request.commandKey(), OP_HOLD_CASE, actorId, requestHash, 200, toView(hold));
    }

    /**
     * 创建证物级冻结：锁定证物行后写入，与该证物的销毁申请互斥串行。
     */
    @Transactional
    public StoredResponse createEvidenceHold(String actorId, String evidenceKey,
                                             HoldCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (evidence.status() == EvidenceStatus.DESTROYED) {
            // 与销毁并发且销毁先提交时，冻结不得再生效。
            throw ApiException.conflict("证物已销毁，不能再创建冻结: " + evidenceKey);
        }
        LocalDateTime nowUtc = clock.nowUtc();
        validateExpireAt(request.expireAt(), nowUtc);
        if (holdRepository.findByHoldKey(request.holdKey()).isPresent()) {
            throw ApiException.conflict("冻结键已存在: " + request.holdKey());
        }
        holdRepository.insert(request.holdKey(), HoldScope.EVIDENCE, evidence.caseKey(), evidenceKey,
                actorId, request.reason(), request.expireAt(), nowUtc);
        RetentionHold hold = holdRepository.findByHoldKey(request.holdKey()).orElseThrow();
        return record(request.commandKey(), OP_HOLD_EVIDENCE, actorId, requestHash, 200, toView(hold));
    }

    /**
     * 解除冻结：解除人必须是不同于创建人的当前保管人，并签明非空原因；解除只影响后续销毁申请。
     */
    @Transactional
    public StoredResponse releaseHold(String actorId, String holdKey,
                                      HoldReleaseRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        RetentionHold hold = holdRepository.findByHoldKeyForUpdate(holdKey)
                .orElseThrow(() -> ApiException.notFound("冻结不存在: " + holdKey));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (hold.createdBy().equals(actorId)) {
            throw ApiException.conflict("冻结创建人不能自行解除，须由其他保管人签明: " + actorId);
        }
        if (!isCustodianOfHoldTarget(hold, actorId)) {
            throw ApiException.conflict("解除人不是冻结对象的当前保管人: " + actorId);
        }
        if (hold.releasedAt() != null) {
            throw ApiException.conflict("冻结已解除: " + holdKey);
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!nowUtc.isBefore(hold.expireAt())) {
            throw ApiException.conflict("冻结已到期，无需解除: " + holdKey);
        }
        if (!holdRepository.release(hold.id(), actorId, request.reason(), nowUtc)) {
            throw ApiException.conflict("冻结已被并发解除: " + holdKey);
        }
        RetentionHold released = holdRepository.findByHoldKey(holdKey).orElseThrow();
        return record(request.commandKey(), OP_HOLD_RELEASE, actorId, requestHash,
                200, toView(released));
    }

    /**
     * 销毁申请：仅当前保管人且证物为 SEALED；同时检查证物级与案件级有效冻结。
     * 被阻断时事务正常提交 REJECTED 记录并返回 409，证物状态不变；通过则证物进入 DESTROYED。
     */
    @Transactional
    public StoredResponse requestDestruction(String actorId, String evidenceKey,
                                             DestructionRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId);
        }
        if (evidence.status() == EvidenceStatus.DESTROYED) {
            throw ApiException.conflict("证物已销毁: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("借出期间禁止销毁: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待接收交接期间禁止销毁: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止销毁: " + evidenceKey);
        }

        LocalDateTime nowUtc = clock.nowUtc();
        LocalDateTime createdAt = LocalDateTime.now();
        // 证物行已持锁：证物级与案件级冻结创建均在该行（或案件全部行）上先加锁，
        // 因此此处读到的冻结集合与销毁决策对并发创建是可串行化的。
        RetentionHold blocker = holdRepository.findEvidenceHoldsForCheck(evidenceKey, nowUtc).stream()
                .filter(h -> isActive(h, nowUtc))
                .findFirst()
                .orElseGet(() -> holdRepository.findCaseHoldsForCheck(evidence.caseKey(), nowUtc).stream()
                        .filter(h -> isActive(h, nowUtc))
                        .findFirst()
                        .orElse(null));
        if (blocker != null) {
            String rejectReason = (blocker.scope() == HoldScope.CASE ? "案件级保留冻结生效中: "
                    : "证物级保留冻结生效中: ") + blocker.holdKey();
            destructionRepository.insert(evidenceKey, actorId, request.reason(),
                    DestructionStatus.REJECTED, rejectReason, blocker.holdKey(), nowUtc, createdAt);
            DestructionView view = new DestructionView(evidenceKey, actorId, request.reason(),
                    DestructionStatus.REJECTED, rejectReason, blocker.holdKey(), nowUtc, createdAt);
            // 正常返回以提交 REJECTED 历史；接口状态码为 409，幂等重放原样返回该结果。
            return record(request.commandKey(), OP_DESTROY, actorId, requestHash, 409, view);
        }

        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.DESTROYED, LocalDateTime.now());
        destructionRepository.insert(evidenceKey, actorId, request.reason(),
                DestructionStatus.APPROVED, null, null, nowUtc, createdAt);
        DestructionView view = new DestructionView(evidenceKey, actorId, request.reason(),
                DestructionStatus.APPROVED, null, null, nowUtc, createdAt);
        return record(request.commandKey(), OP_DESTROY, actorId, requestHash, 200, view);
    }

    /**
     * 查询案件全部案件级冻结（含生效、已解除、已到期）。
     */
    @Transactional(readOnly = true)
    public CaseHoldsView listCaseHolds(String caseKey) {
        List<HoldView> holds = holdRepository.findCaseHolds(caseKey).stream()
                .map(this::toView).toList();
        return new CaseHoldsView(caseKey, holds);
    }

    /**
     * 查询证物两级冻结：所属案件的案件级冻结 + 该证物的证物级冻结。
     */
    @Transactional(readOnly = true)
    public EvidenceHoldsView listEvidenceHolds(String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        List<HoldView> caseHolds = holdRepository.findCaseHolds(evidence.caseKey()).stream()
                .map(this::toView).toList();
        List<HoldView> evidenceHolds = holdRepository.findEvidenceHolds(evidenceKey).stream()
                .map(this::toView).toList();
        return new EvidenceHoldsView(evidence.caseKey(), evidenceKey, caseHolds, evidenceHolds);
    }

    /**
     * 查询证物全部销毁申请历史（含 REJECTED，按发生顺序）。
     */
    @Transactional(readOnly = true)
    public List<DestructionView> listDestructions(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        return destructionRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toView).toList();
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void validateExpireAt(LocalDateTime expireAt, LocalDateTime nowUtc) {
        if (!expireAt.isAfter(nowUtc)) {
            throw ApiException.badRequest("冻结到期时刻须晚于服务端当前 UTC 时刻");
        }
    }

    /**
     * 冻结在指定 UTC 时刻是否阻断销毁：未解除，且窗口左闭右开 [createdAt, expireAt)。
     */
    private boolean isActive(RetentionHold hold, LocalDateTime nowUtc) {
        return hold.releasedAt() == null
                && !nowUtc.isBefore(hold.createdAt())
                && nowUtc.isBefore(hold.expireAt());
    }

    /**
     * 保管人判定：证物级为该证物当前保管人；案件级为案件下任一证物的当前保管人。
     */
    private boolean isCustodianOfHoldTarget(RetentionHold hold, String actorId) {
        if (hold.scope() == HoldScope.EVIDENCE) {
            return evidenceRepository.findByKey(hold.evidenceKey())
                    .map(e -> e.custodianId().equals(actorId)).orElse(false);
        }
        return evidenceRepository.findByCaseKey(hold.caseKey()).stream()
                .anyMatch(e -> e.custodianId().equals(actorId));
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
        HoldState state;
        if (hold.releasedAt() != null) {
            state = HoldState.RELEASED;
        } else if (!nowUtc.isBefore(hold.expireAt())) {
            // 左闭右开：当前时刻不早于到期时刻即视为到期，恰好到期不再阻断。
            state = HoldState.EXPIRED;
        } else {
            state = HoldState.ACTIVE;
        }
        return new HoldView(hold.holdKey(), hold.scope(), hold.caseKey(), hold.evidenceKey(),
                hold.createdBy(), hold.reason(), hold.expireAt(), state, state == HoldState.ACTIVE,
                hold.releasedBy(), hold.releaseReason(), hold.releasedAt(), hold.createdAt());
    }

    private DestructionView toView(DestructionRecord record) {
        return new DestructionView(record.evidenceKey(), record.requestedBy(), record.reason(),
                record.status(), record.rejectReason(), record.blockingHoldKey(),
                record.decidedAt(), record.createdAt());
    }

    /**
     * 供保管链组装使用的冻结视图映射（查询时刻状态由时钟即时计算）。
     */
    HoldView viewHold(RetentionHold hold) {
        return toView(hold);
    }

    /**
     * 供保管链组装使用的销毁记录视图映射。
     */
    DestructionView viewDestruction(DestructionRecord record) {
        return toView(record);
    }
}
