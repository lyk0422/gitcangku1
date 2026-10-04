package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CheckinView;
import com.example.starter.evidence.dto.CheckpointView;
import com.example.starter.evidence.dto.ExceptionView;
import com.example.starter.evidence.dto.RegulatedCheckinRequest;
import com.example.starter.evidence.dto.RegulatedDecisionRequest;
import com.example.starter.evidence.dto.RegulatedTransferInitiateRequest;
import com.example.starter.evidence.dto.RegulatedTransferView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 受监管交接核心服务。当前保管人发起时声明 2-8 个有序检查点及每段 UTC 截止时刻，
 * 名单与封条创建后冻结；运送人按点号顺序签到，超时或封条不一致记录异常并冻结交接。
 * 所有写操作在单事务内完成：先锁定证物行，权限校验先于幂等重放，业务与幂等日志原子提交。
 */
@Service
public class RegulatedTransferService {

    static final String OP_INITIATE = "REG_TRANSFER_INITIATE";
    static final String OP_CHECKIN = "REG_TRANSFER_CHECKIN";
    static final String OP_ACCEPT = "REG_TRANSFER_ACCEPT";
    static final String OP_CANCEL = "REG_TRANSFER_CANCEL";

    private final EvidenceRepository evidenceRepository;
    private final RegulatedTransferRepository transferRepository;
    private final RegulatedCheckpointRepository checkpointRepository;
    private final RegulatedCheckinRepository checkinRepository;
    private final RegulatedExceptionRepository exceptionRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public RegulatedTransferService(EvidenceRepository evidenceRepository,
                                    RegulatedTransferRepository transferRepository,
                                    RegulatedCheckpointRepository checkpointRepository,
                                    RegulatedCheckinRepository checkinRepository,
                                    RegulatedExceptionRepository exceptionRepository,
                                    CommandLogRepository commandLogRepository,
                                    ObjectMapper objectMapper,
                                    EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.checkpointRepository = checkpointRepository;
        this.checkinRepository = checkinRepository;
        this.exceptionRepository = exceptionRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 发起受监管交接：仅当前保管人（越权 403 且先于重放）；证物须为 SEALED；
     * 截止时刻须晚于当前 UTC 时刻且严格递增。成功后证物进入 TRANSFER_PENDING。
     */
    @Transactional
    public StoredResponse initiate(String actorId, String evidenceKey,
                                   RegulatedTransferInitiateRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.forbidden("操作人不是当前保管人: " + actorId);
        }
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (request.toCustodian().equals(actorId)) {
            throw ApiException.badRequest("收件人不能与发起保管人相同");
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止发起交接: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("证物已存在在途交接: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("借出期间禁止发起交接: " + evidenceKey);
        }
        LocalDateTime previous = clock.nowUtc();
        for (RegulatedTransferInitiateRequest.CheckpointSpec spec : request.checkpoints()) {
            if (!spec.deadlineAt().isAfter(previous)) {
                throw ApiException.badRequest("截止时刻须晚于当前时刻且按点号严格递增");
            }
            previous = spec.deadlineAt();
        }
        String transferKey = "RT-" + UUID.randomUUID();
        LocalDateTime nowUtc = clock.nowUtc();
        transferRepository.insert(transferKey, evidenceKey, actorId, request.toCustodian(),
                request.transporterId(), evidence.sealNo(), request.checkpoints().size(), nowUtc);
        for (int i = 0; i < request.checkpoints().size(); i++) {
            checkpointRepository.insert(transferKey, i + 1, request.checkpoints().get(i).deadlineAt());
        }
        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.TRANSFER_PENDING, LocalDateTime.now());
        RegulatedTransfer saved = transferRepository.findByKey(transferKey).orElseThrow();
        return record(request.requestId(), OP_INITIATE, actorId, requestHash, 201, toView(saved));
    }

    /**
     * 检查点签到：仅指定运送人（越权 403 且先于重放）；交接须在途且版本匹配；
     * 点号须为当前期望点号，到达时刻不得早于上一点。超时或封条不一致记录异常并冻结交接，
     * 异常签到同样留痕，响应中交接状态为 FROZEN。
     */
    @Transactional
    public StoredResponse checkin(String actorId, String evidenceKey,
                                  RegulatedCheckinRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        Optional<RegulatedTransfer> open = transferRepository.findOpenByEvidenceKey(evidenceKey);
        if (open.isPresent() && !open.get().transporterId().equals(actorId)) {
            throw ApiException.forbidden("操作人不是指定运送人: " + actorId);
        }
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        RegulatedTransfer transfer = open.orElseThrow(
                () -> ApiException.conflict("证物不存在在途受监管交接: " + evidenceKey));
        if (transfer.status() == RegulatedTransferStatus.FROZEN) {
            throw ApiException.conflict("交接已冻结，禁止继续签到: " + evidenceKey);
        }
        requireVersion(transfer, request.expectedVersion());
        List<RegulatedCheckin> checkins = checkinRepository.findByTransferKey(transfer.transferKey());
        int expectedNo = checkins.size() + 1;
        if (request.checkpointNo() != expectedNo) {
            throw ApiException.conflict("必须按顺序签到，当前期望点号: " + expectedNo);
        }
        if (!checkins.isEmpty()
                && request.arrivedAt().isBefore(checkins.get(checkins.size() - 1).arrivedAt())) {
            throw ApiException.conflict("到达时刻不得早于上一检查点到达时刻");
        }
        List<RegulatedCheckpoint> checkpoints =
                checkpointRepository.findByTransferKey(transfer.transferKey());
        RegulatedCheckpoint checkpoint = checkpoints.get(expectedNo - 1);
        LocalDateTime nowUtc = clock.nowUtc();
        checkinRepository.insert(transfer.transferKey(), expectedNo, actorId,
                request.sealNo(), request.arrivedAt(), nowUtc);
        RegulatedExceptionReason reason = null;
        String detail = null;
        if (!request.sealNo().equals(transfer.sealNo())) {
            reason = RegulatedExceptionReason.SEAL_MISMATCH;
            detail = "提交封条 " + request.sealNo() + " 与冻结封条 " + transfer.sealNo() + " 不一致";
        } else if (request.arrivedAt().isAfter(checkpoint.deadlineAt())) {
            reason = RegulatedExceptionReason.TIMEOUT;
            detail = "到达时刻 " + request.arrivedAt() + " 晚于截止时刻 " + checkpoint.deadlineAt();
        }
        if (reason != null) {
            exceptionRepository.insert(transfer.transferKey(), expectedNo, reason, detail, nowUtc);
            if (!transferRepository.transition(transfer.id(), RegulatedTransferStatus.IN_TRANSIT,
                    RegulatedTransferStatus.FROZEN, request.expectedVersion(), null)) {
                throw ApiException.conflict("交接已被并发变更: " + evidenceKey);
            }
        } else if (!transferRepository.bumpVersion(transfer.id(), request.expectedVersion())) {
            throw ApiException.conflict("交接已被并发变更: " + evidenceKey);
        }
        RegulatedTransfer saved = transferRepository.findByKey(transfer.transferKey()).orElseThrow();
        return record(request.requestId(), OP_CHECKIN, actorId, requestHash, 200, toView(saved));
    }
    /**
     * 接收受监管交接：仅指定收件人（越权 403 且先于重放）；交接须在途、版本匹配且
     * 全部检查点已正常签到，签到缺失返回 422，冻结交接返回 409。
     * 成功后保管人原子切换为收件人，证物回到 SEALED，检查点与封条保持冻结值。
     */
    @Transactional
    public StoredResponse accept(String actorId, String evidenceKey,
                                 RegulatedDecisionRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        Optional<RegulatedTransfer> open = transferRepository.findOpenByEvidenceKey(evidenceKey);
        if (open.isPresent() && !open.get().toCustodian().equals(actorId)) {
            throw ApiException.forbidden("操作人不是指定收件人: " + actorId);
        }
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        RegulatedTransfer transfer = open.orElseThrow(
                () -> ApiException.conflict("证物不存在在途受监管交接: " + evidenceKey));
        if (transfer.status() == RegulatedTransferStatus.FROZEN) {
            throw ApiException.conflict("交接已冻结，收件人不能接收: " + evidenceKey);
        }
        requireVersion(transfer, request.expectedVersion());
        List<RegulatedCheckin> checkins = checkinRepository.findByTransferKey(transfer.transferKey());
        if (checkins.size() < transfer.checkpointCount()) {
            throw ApiException.unprocessable("签到缺失，不能接收: 已完成 " + checkins.size()
                    + "/" + transfer.checkpointCount());
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!transferRepository.transition(transfer.id(), RegulatedTransferStatus.IN_TRANSIT,
                RegulatedTransferStatus.COMPLETED, request.expectedVersion(), nowUtc)) {
            throw ApiException.conflict("交接已被并发变更: " + evidenceKey);
        }
        evidenceRepository.updateCustody(evidenceKey, actorId, EvidenceStatus.SEALED,
                LocalDateTime.now());
        RegulatedTransfer saved = transferRepository.findByKey(transfer.transferKey()).orElseThrow();
        return record(request.requestId(), OP_ACCEPT, actorId, requestHash, 200, toView(saved));
    }

    /**
     * 取消受监管交接：仅原保管人（越权 403 且先于重放）；在途或冻结均可取消。
     * 证物回到 SEALED，已记录的签到与异常保留，可重新发起新交接。
     */
    @Transactional
    public StoredResponse cancel(String actorId, String evidenceKey,
                                 RegulatedDecisionRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        Optional<RegulatedTransfer> open = transferRepository.findOpenByEvidenceKey(evidenceKey);
        if (open.isPresent() && !open.get().fromCustodian().equals(actorId)) {
            throw ApiException.forbidden("仅原保管人可取消交接: " + actorId);
        }
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        RegulatedTransfer transfer = open.orElseThrow(
                () -> ApiException.conflict("证物不存在在途受监管交接: " + evidenceKey));
        requireVersion(transfer, request.expectedVersion());
        LocalDateTime nowUtc = clock.nowUtc();
        if (!transferRepository.transition(transfer.id(), transfer.status(),
                RegulatedTransferStatus.CANCELLED, request.expectedVersion(), nowUtc)) {
            throw ApiException.conflict("交接已被并发变更: " + evidenceKey);
        }
        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.SEALED, LocalDateTime.now());
        RegulatedTransfer saved = transferRepository.findByKey(transfer.transferKey()).orElseThrow();
        return record(request.requestId(), OP_CANCEL, actorId, requestHash, 200, toView(saved));
    }

    /**
     * 按发生顺序查询证物全部受监管交接（含检查点名单、签到与异常记录）。
     * 历史链只读不改，重新发起的新交接追加为新记录。
     */
    @Transactional(readOnly = true)
    public List<RegulatedTransferView> listByEvidence(String evidenceKey) {
        evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        return transferRepository.findByEvidenceKey(evidenceKey).stream()
                .map(this::toView)
                .toList();
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void requireVersion(RegulatedTransfer transfer, long expectedVersion) {
        if (transfer.version() != expectedVersion) {
            throw ApiException.conflict("期望版本不一致: 期望 " + expectedVersion
                    + "，当前 " + transfer.version());
        }
    }

    private Optional<StoredResponse> checkReplay(String requestId, String requestHash) {
        return commandLogRepository.findByKey(requestId).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + requestId);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String requestId, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(requestId, actorId, operation, requestHash, status, json,
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

    private RegulatedTransferView toView(RegulatedTransfer transfer) {
        List<CheckpointView> checkpoints = checkpointRepository
                .findByTransferKey(transfer.transferKey()).stream()
                .map(c -> new CheckpointView(c.checkpointNo(), c.deadlineAt()))
                .toList();
        List<CheckinView> checkins = checkinRepository
                .findByTransferKey(transfer.transferKey()).stream()
                .map(c -> new CheckinView(c.checkpointNo(), c.transporterId(), c.sealNo(),
                        c.arrivedAt()))
                .toList();
        List<ExceptionView> exceptions = exceptionRepository
                .findByTransferKey(transfer.transferKey()).stream()
                .map(e -> new ExceptionView(e.checkpointNo(), e.reason(), e.detail(),
                        e.createdAt()))
                .toList();
        return new RegulatedTransferView(transfer.transferKey(), transfer.evidenceKey(),
                transfer.fromCustodian(), transfer.toCustodian(), transfer.transporterId(),
                transfer.sealNo(), transfer.checkpointCount(), transfer.version(),
                transfer.status(), transfer.createdAt(), transfer.decidedAt(),
                checkpoints, checkins, exceptions);
    }
}