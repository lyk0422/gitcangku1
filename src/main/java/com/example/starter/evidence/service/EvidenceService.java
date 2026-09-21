package com.example.starter.evidence.service;

import com.example.starter.evidence.domain.Evidence;
import com.example.starter.evidence.domain.EvidenceStatus;
import com.example.starter.evidence.domain.SealCheck;
import com.example.starter.evidence.domain.SealCheckResult;
import com.example.starter.evidence.domain.Transfer;
import com.example.starter.evidence.domain.TransferStatus;
import com.example.starter.evidence.repository.EvidenceRepository;
import com.example.starter.evidence.repository.SealCheckRepository;
import com.example.starter.evidence.repository.TransferRepository;
import com.example.starter.evidence.web.ApiException;
import com.example.starter.evidence.web.dto.CustodyChainResponse;
import com.example.starter.evidence.web.dto.EvidenceResponse;
import com.example.starter.evidence.web.dto.IntakeRequest;
import com.example.starter.evidence.web.dto.SealCheckRequest;
import com.example.starter.evidence.web.dto.SealCheckResponse;
import com.example.starter.evidence.web.dto.TransferInitiateRequest;
import com.example.starter.evidence.web.dto.TransferResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 证物封存交接核心业务。所有变更方法在事务内先对证物行加锁（FOR UPDATE），
 * 交接接受/取消与失败核验按事务提交顺序生效，杜绝已取消交接仍变更保管人等竞态。
 */
@Service
public class EvidenceService {

    private final EvidenceRepository evidenceRepository;
    private final TransferRepository transferRepository;
    private final SealCheckRepository sealCheckRepository;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           TransferRepository transferRepository,
                           SealCheckRepository sealCheckRepository) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.sealCheckRepository = sealCheckRepository;
    }

    /**
     * 入库：evidenceKey 全局唯一，初始状态 SEALED，业务字段此后不可修改。
     */
    @Transactional
    public EvidenceResponse intake(String actorId, IntakeRequest request) {
        evidenceRepository.findByKey(request.evidenceKey()).ifPresent(existing -> {
            throw ApiException.conflict("evidenceKey 已存在: " + request.evidenceKey());
        });
        LocalDateTime now = LocalDateTime.now();
        long id = evidenceRepository.insert(
                request.evidenceKey(), request.caseKey(), request.category(),
                request.sealNo(), request.custodianId(), now);
        Evidence evidence = new Evidence(id, request.evidenceKey(), request.caseKey(),
                request.category(), request.sealNo(), request.custodianId(),
                EvidenceStatus.SEALED, now, now);
        return EvidenceResponse.from(evidence);
    }

    /**
     * 发起交接：仅当前保管人、仅 SEALED 状态可发起；接收人必须不同；进入 TRANSFER_PENDING。
     */
    @Transactional
    public TransferResponse initiateTransfer(String actorId, String evidenceKey, TransferInitiateRequest request) {
        Evidence evidence = lockEvidence(evidenceKey);
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.sealBroken("封条已异常，禁止发起交接");
        }
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅当前保管人可发起交接");
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("存在待接收交接，禁止重复发起");
        }
        if (request.toCustodianId().equals(evidence.custodianId())) {
            throw ApiException.badRequest("接收人必须与当前保管人不同");
        }
        LocalDateTime now = LocalDateTime.now();
        long transferId = transferRepository.insert(
                evidence.id(), evidence.custodianId(), request.toCustodianId(), now);
        evidenceRepository.updateStatus(evidence.id(), EvidenceStatus.TRANSFER_PENDING, now);
        Transfer transfer = new Transfer(transferId, evidence.id(), evidence.custodianId(),
                request.toCustodianId(), TransferStatus.PENDING, now, null);
        return TransferResponse.from(transfer, evidence.evidenceKey());
    }

    /**
     * 接受交接：仅指定接收人可接受；原子切换保管人并回到 SEALED。
     */
    @Transactional
    public TransferResponse acceptTransfer(String actorId, String evidenceKey) {
        Evidence evidence = lockEvidence(evidenceKey);
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.sealBroken("封条已异常，禁止接受交接");
        }
        Transfer pending = pendingTransfer(evidence);
        if (!pending.toCustodianId().equals(actorId)) {
            throw ApiException.conflict("仅指定接收人可接受交接");
        }
        LocalDateTime now = LocalDateTime.now();
        transferRepository.decide(pending.id(), TransferStatus.ACCEPTED, now);
        evidenceRepository.updateCustodianAndStatus(evidence.id(), actorId, EvidenceStatus.SEALED, now);
        Transfer accepted = new Transfer(pending.id(), pending.evidenceId(), pending.fromCustodianId(),
                pending.toCustodianId(), TransferStatus.ACCEPTED, pending.createdAt(), now);
        return TransferResponse.from(accepted, evidence.evidenceKey());
    }

    /**
     * 取消交接：仅原保管人（发起方）可取消，接收人不能自行取消；证物回到 SEALED。
     */
    @Transactional
    public TransferResponse cancelTransfer(String actorId, String evidenceKey) {
        Evidence evidence = lockEvidence(evidenceKey);
        Transfer pending = pendingTransfer(evidence);
        if (!pending.fromCustodianId().equals(actorId)) {
            throw ApiException.conflict("仅原保管人可取消交接");
        }
        LocalDateTime now = LocalDateTime.now();
        transferRepository.decide(pending.id(), TransferStatus.CANCELLED, now);
        evidenceRepository.updateStatus(evidence.id(), EvidenceStatus.SEALED, now);
        Transfer cancelled = new Transfer(pending.id(), pending.evidenceId(), pending.fromCustodianId(),
                pending.toCustodianId(), TransferStatus.CANCELLED, pending.createdAt(), now);
        return TransferResponse.from(cancelled, evidence.evidenceKey());
    }

    /**
     * 封条核验：仅当前保管人、仅 SEALED 状态可提交；PASS 仅追加不可变记录，FAIL 进入 SEAL_BROKEN 终态。
     */
    @Transactional
    public SealCheckResponse sealCheck(String actorId, String evidenceKey, SealCheckRequest request) {
        Evidence evidence = lockEvidence(evidenceKey);
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.sealBroken("封条已异常，禁止再提交核验");
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("交接待接收期间禁止封条核验");
        }
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅当前保管人可提交封条核验");
        }
        LocalDateTime now = LocalDateTime.now();
        long checkId = sealCheckRepository.insert(
                evidence.id(), actorId, request.result(), request.detail(), now);
        if (request.result() == SealCheckResult.FAIL) {
            evidenceRepository.updateStatus(evidence.id(), EvidenceStatus.SEAL_BROKEN, now);
        }
        SealCheck check = new SealCheck(checkId, evidence.id(), actorId,
                request.result(), request.detail(), now);
        return SealCheckResponse.from(check, evidence.evidenceKey());
    }

    /**
     * 当前可交接证物：本人保管且状态 SEALED。
     */
    @Transactional(readOnly = true)
    public List<EvidenceResponse> findTransferable(String actorId) {
        return evidenceRepository.findTransferable(actorId).stream()
                .map(EvidenceResponse::from)
                .toList();
    }

    /**
     * 完整保管链：入库、交接与核验事件按时间升序，全部来自只追加历史。
     */
    @Transactional(readOnly = true)
    public CustodyChainResponse custodyChain(String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        List<CustodyChainResponse.ChainEvent> events = new ArrayList<>();
        events.add(new CustodyChainResponse.ChainEvent("INTAKE", evidence.custodianId(),
                null, null, null, null, evidence.createdAt()));
        for (Transfer transfer : transferRepository.findByEvidenceId(evidence.id())) {
            events.add(new CustodyChainResponse.ChainEvent("TRANSFER_INITIATE", transfer.fromCustodianId(),
                    transfer.fromCustodianId(), transfer.toCustodianId(), null, null, transfer.createdAt()));
            if (transfer.status() == TransferStatus.ACCEPTED) {
                events.add(new CustodyChainResponse.ChainEvent("TRANSFER_ACCEPT", transfer.toCustodianId(),
                        transfer.fromCustodianId(), transfer.toCustodianId(), null, null, transfer.decidedAt()));
            } else if (transfer.status() == TransferStatus.CANCELLED) {
                events.add(new CustodyChainResponse.ChainEvent("TRANSFER_CANCEL", transfer.fromCustodianId(),
                        transfer.fromCustodianId(), transfer.toCustodianId(), null, null, transfer.decidedAt()));
            }
        }
        for (SealCheck check : sealCheckRepository.findByEvidenceId(evidence.id())) {
            events.add(new CustodyChainResponse.ChainEvent("SEAL_CHECK", check.actorId(),
                    null, null, check.result().name(), check.detail(), check.createdAt()));
        }
        events.sort(Comparator.comparing(CustodyChainResponse.ChainEvent::occurredAt));
        return new CustodyChainResponse(evidence.evidenceKey(), events);
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private Transfer pendingTransfer(Evidence evidence) {
        return transferRepository.findPendingByEvidenceId(evidence.id())
                .orElseThrow(() -> ApiException.conflict("当前不存在待接收的交接"));
    }
}
