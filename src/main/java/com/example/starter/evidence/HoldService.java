package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CaseHoldsView;
import com.example.starter.evidence.dto.DestructionView;
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
 * 保留冻结服务：案件级与单件证物级冻结的创建、解除与查询。
 * 写操作在单事务内完成：创建先锁案件下证物行（证物级先锁证物行）再追加冻结，
 * 与销毁事务在同一组行锁上串行；解除锁冻结行做一次条件更新。
 * 冻结窗口按 UTC 左闭右开，到期为查询时派生，不改写记录；解除只影响其后的销毁申请。
 */
@Service
public class HoldService {

    static final String OP_HOLD_CREATE = "HOLD_CREATE";
    static final String OP_HOLD_RELEASE = "HOLD_RELEASE";

    private final RetentionHoldRepository holdRepository;
    private final EvidenceRepository evidenceRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public HoldService(RetentionHoldRepository holdRepository,
                       EvidenceRepository evidenceRepository,
                       CommandLogRepository commandLogRepository,
                       ObjectMapper objectMapper,
                       EvidenceClock clock) {
        this.holdRepository = holdRepository;
        this.evidenceRepository = evidenceRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建案件级冻结：作用于案件下全部证物（含其后入库的证物）。
     * 锁定案件下现有证物行，保证与这些证物的并发销毁按提交顺序串行，
     * 不出现“案件冻结已生效而证物同时被销毁”。
     */
    @Transactional
    public StoredResponse createCaseHold(String actorId, String caseKey,
                                         HoldCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 案件下尚无证物时返回空列表：此刻没有可销毁对象；销毁判定仍会在证物行锁内复查案件冻结。
        evidenceRepository.findByCaseKeyForUpdate(caseKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LocalDateTime nowUtc = clock.nowUtc();
        validateExpiry(request.expiresAt(), nowUtc);
        LocalDateTime createdAt = LocalDateTime.now();
        holdRepository.insertCaseHold(request.commandKey(), caseKey, actorId,
                request.expiresAt(), request.reason(), createdAt);
        RetentionHold hold = holdRepository.findByHoldRequestId(request.commandKey()).orElseThrow();
        return record(request.commandKey(), OP_HOLD_CREATE, actorId, requestHash, 201, toView(hold));
    }

    /**
     * 创建单件证物级冻结：证物必须存在且未销毁；与该证物的销毁在证物行锁上串行。
     */
    @Transactional
    public StoredResponse createEvidenceHold(String actorId, String evidenceKey,
                                             HoldCreateRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (evidence.status() == EvidenceStatus.DESTROYED) {
            throw ApiException.conflict("证物已销毁，不能再设置保留冻结: " + evidenceKey);
        }
        LocalDateTime nowUtc = clock.nowUtc();
        validateExpiry(request.expiresAt(), nowUtc);
        holdRepository.insertEvidenceHold(request.commandKey(), evidenceKey, evidence.caseKey(),
                actorId, request.expiresAt(), request.reason(), LocalDateTime.now());
        RetentionHold hold = holdRepository.findByHoldRequestId(request.commandKey()).orElseThrow();
        return record(request.commandKey(), OP_HOLD_CREATE, actorId, requestHash, 201, toView(hold));
    }

    /**
     * 解除冻结：操作人必须不同于创建人并写明原因；仅未解除冻结可解除。
     * 解除不影响已被拒的销毁申请，只影响其后新申请的冻结判定。
     */
    @Transactional
    public StoredResponse releaseHold(String actorId, String holdRequestId,
                                      HoldReleaseRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        RetentionHold hold = holdRepository.findByHoldRequestIdForUpdate(holdRequestId)
                .orElseThrow(() -> ApiException.notFound("冻结不存在: " + holdRequestId));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (hold.createdBy().equals(actorId)) {
            throw ApiException.conflict("解除须由不同于创建人的保管人签明: " + actorId);
        }
        if (hold.status() == HoldStatus.RELEASED) {
            throw ApiException.conflict("冻结已解除: " + holdRequestId);
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!holdRepository.release(hold.id(), actorId, request.reason(),
                request.commandKey(), nowUtc)) {
            throw ApiException.conflict("冻结已被并发解除: " + holdRequestId);
        }
        RetentionHold released = holdRepository.findByHoldRequestId(holdRequestId).orElseThrow();
        return record(request.commandKey(), OP_HOLD_RELEASE, actorId, requestHash,
                200, toView(released));
    }

    /**
     * 查询案件两级冻结总览（案件级 + 案件下证物的证物级）。
     */
    @Transactional(readOnly = true)
    public CaseHoldsView caseHolds(String caseKey) {
        List<HoldView> holds = holdRepository.findByCaseKey(caseKey).stream()
                .map(this::toView).toList();
        return new CaseHoldsView(caseKey, holds);
    }

    /**
     * 查询单件证物的证物级冻结。
     */
    @Transactional(readOnly = true)
    public List<HoldView> evidenceHolds(String evidenceKey) {
        return holdRepository.findByEvidenceKey(evidenceKey).stream().map(this::toView).toList();
    }

    private void validateExpiry(LocalDateTime expiresAt, LocalDateTime nowUtc) {
        if (!expiresAt.isAfter(nowUtc)) {
            throw ApiException.badRequest("冻结到期时刻须晚于服务端当前 UTC 时刻");
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

    /**
     * 冻结视图转换：effective 按当前 UTC 时钟派生（ACTIVE 且 now &lt; expiresAt）。
     */
    public HoldView toView(RetentionHold hold) {
        return new HoldView(hold.holdRequestId(), hold.level(), hold.caseKey(), hold.scopeKey(),
                hold.evidenceKey(), hold.expiresAt(), hold.reason(), hold.createdBy(),
                hold.status(), hold.effectiveAt(clock.nowUtc()), hold.createdAt(),
                hold.releasedBy(), hold.releaseReason(), hold.releaseRequestId(),
                hold.releasedAt());
    }

    /**
     * 销毁申请视图转换（供保管链与销毁响应共用）。
     */
    public DestructionView toView(DestructionRequest request) {
        return new DestructionView(request.evidenceKey(), request.requestId(),
                request.requestedBy(), request.reason(), request.status(),
                request.blockingHoldId(), request.requestedAt());
    }
}
