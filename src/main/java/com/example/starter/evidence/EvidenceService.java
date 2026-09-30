package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.ContainerCreateRequest;
import com.example.starter.evidence.dto.ContainerInspectionRequest;
import com.example.starter.evidence.dto.ContainerInspectionView;
import com.example.starter.evidence.dto.ContainerPlaceRequest;
import com.example.starter.evidence.dto.ContainerView;
import com.example.starter.evidence.dto.CustodyChainView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.InspectionItemView;
import com.example.starter.evidence.dto.InspectionReviewRequest;
import com.example.starter.evidence.dto.InspectionReviewView;
import com.example.starter.evidence.dto.InspectionView;
import com.example.starter.evidence.dto.InspectionWitnessView;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LoanReturnRequest;
import com.example.starter.evidence.dto.LoanView;
import com.example.starter.evidence.dto.LocationView;
import com.example.starter.evidence.dto.SealInspectionRequest;
import com.example.starter.evidence.dto.SealReplaceRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import com.example.starter.evidence.dto.TransferView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 证物封存交接核心服务。所有写操作在单事务内完成：
 * 先锁定容器/证物行（SELECT ... FOR UPDATE，统一先容器后证物的加锁顺序），再校验状态机并落库，
 * 最后写入幂等日志。交接、核验、借出、位置轨迹记录只追加；证物业务字段入库后不可修改。
 *
 * <p>容器巡检采用两名不同保管人凭同一 requestId 双人确认：第一见证人建单（不改变证物状态），
 * 第二见证人版本与结论一致且封条异常时，单事务把巡检时仍在箱内的全部证物一次转为
 * QUARANTINED，并冻结原位置/原保管人快照；复核放行前禁止借出、归还与交接。
 */
@Service
public class EvidenceService {

    static final String OP_INTAKE = "INTAKE";
    static final String OP_TRANSFER_INITIATE = "TRANSFER_INITIATE";
    static final String OP_TRANSFER_ACCEPT = "TRANSFER_ACCEPT";
    static final String OP_TRANSFER_CANCEL = "TRANSFER_CANCEL";
    static final String OP_SEAL_INSPECTION = "SEAL_INSPECTION";
    static final String OP_LOAN_BORROW = "LOAN_BORROW";
    static final String OP_LOAN_RETURN = "LOAN_RETURN";
    static final String OP_CONTAINER_CREATE = "CONTAINER_CREATE";
    static final String OP_SEAL_REPLACE = "SEAL_REPLACE";
    static final String OP_CONTAINER_PLACE = "CONTAINER_PLACE";
    static final String OP_INSPECTION_REVIEW = "INSPECTION_REVIEW";

    /**
     * 借出最长期限：72 小时。
     */
    static final long MAX_LOAN_HOURS = 72;

    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final SealInspectionRepository inspectionRepository;
    private final LoanRecordRepository loanRepository;
    private final CommandLogRepository commandLogRepository;
    private final ContainerRepository containerRepository;
    private final ContainerInspectionRepository containerInspectionRepository;
    private final InspectionItemRepository inspectionItemRepository;
    private final InspectionReviewRepository inspectionReviewRepository;
    private final LocationSnapshotRepository locationSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           TransferRecordRepository transferRepository,
                           SealInspectionRepository inspectionRepository,
                           LoanRecordRepository loanRepository,
                           CommandLogRepository commandLogRepository,
                           ContainerRepository containerRepository,
                           ContainerInspectionRepository containerInspectionRepository,
                           InspectionItemRepository inspectionItemRepository,
                           InspectionReviewRepository inspectionReviewRepository,
                           LocationSnapshotRepository locationSnapshotRepository,
                           ObjectMapper objectMapper,
                           EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.loanRepository = loanRepository;
        this.commandLogRepository = commandLogRepository;
        this.containerRepository = containerRepository;
        this.containerInspectionRepository = containerInspectionRepository;
        this.inspectionItemRepository = inspectionItemRepository;
        this.inspectionReviewRepository = inspectionReviewRepository;
        this.locationSnapshotRepository = locationSnapshotRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 证物入库：初始状态 SEALED，保管人为操作人，未入容器。evidenceKey 全局唯一。
     */
    @Transactional
    public StoredResponse intake(String actorId, IntakeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (evidenceRepository.findByKey(request.evidenceKey()).isPresent()) {
            throw ApiException.conflict("证物已存在: " + request.evidenceKey());
        }
        LocalDateTime now = LocalDateTime.now();
        evidenceRepository.insert(request.evidenceKey(), request.caseKey(), request.category(),
                request.sealNo(), actorId, now);
        Evidence evidence = evidenceRepository.findByKey(request.evidenceKey()).orElseThrow();
        appendLocation(evidence, LocationReason.INTAKE, null, now);
        return record(request.commandKey(), OP_INTAKE, actorId, requestHash, 201, toView(evidence));
    }

    /**
     * 发起交接：仅当前保管人，证物须为 SEALED，接收人须与发起人不同；成功后进入 TRANSFER_PENDING。
     */
    @Transactional
    public StoredResponse initiateTransfer(String actorId, String evidenceKey,
                                           TransferInitiateRequest request, String requestHash) {
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
        if (request.toCustodian().equals(actorId)) {
            throw ApiException.badRequest("接收人不能与发起保管人相同");
        }
        requireSealIntact(evidence);
        requireNotQuarantined(evidence);
        requireCustodian(evidence, actorId);
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("证物已存在待接收交接: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("借出期间禁止发起交接: " + evidenceKey);
        }
        LocalDateTime now = LocalDateTime.now();
        transferRepository.insert(evidenceKey, actorId, request.toCustodian(), now);
        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.TRANSFER_PENDING, now);
        TransferRecord pending = transferRepository.findPendingByEvidenceKey(evidenceKey).orElseThrow();
        return record(request.commandKey(), OP_TRANSFER_INITIATE, actorId, requestHash,
                200, toView(pending));
    }

    /**
     * 接受交接：仅指定接收人；保管人原子切换为接收人并回到 SEALED，追加位置轨迹。
     */
    @Transactional
    public StoredResponse acceptTransfer(String actorId, String evidenceKey,
                                         CommandRequest request, String requestHash) {
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
        requireSealIntact(evidence);
        requireNotQuarantined(evidence);
        TransferRecord pending = requirePending(evidence, evidenceKey);
        if (!pending.toCustodian().equals(actorId)) {
            throw ApiException.conflict("操作人不是指定接收人: " + actorId);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!transferRepository.decide(pending.id(), TransferStatus.ACCEPTED, now)) {
            throw ApiException.conflict("交接已被处理: " + evidenceKey);
        }
        evidenceRepository.updateCustody(evidenceKey, actorId, EvidenceStatus.SEALED, now);
        Evidence updated = evidenceRepository.findByKey(evidenceKey).orElseThrow();
        appendLocation(updated, LocationReason.TRANSFER_ACCEPT, null, now);
        return record(request.commandKey(), OP_TRANSFER_ACCEPT, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 取消交接：仅原保管人（发起人）可取消，接收人不能自行取消；证物回到 SEALED。
     */
    @Transactional
    public StoredResponse cancelTransfer(String actorId, String evidenceKey,
                                         CommandRequest request, String requestHash) {
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
        requireSealIntact(evidence);
        requireNotQuarantined(evidence);
        TransferRecord pending = requirePending(evidence, evidenceKey);
        if (!pending.fromCustodian().equals(actorId)) {
            throw ApiException.conflict("仅原保管人可取消交接: " + actorId);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!transferRepository.decide(pending.id(), TransferStatus.CANCELLED, now)) {
            throw ApiException.conflict("交接已被处理: " + evidenceKey);
        }
        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.SEALED, now);
        Evidence updated = evidenceRepository.findByKey(evidenceKey).orElseThrow();
        return record(request.commandKey(), OP_TRANSFER_CANCEL, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 封条核验：仅当前保管人，待接收或隔离复核期间禁止核验。
     * 通过仅追加不可变记录；失败使证物进入 SEAL_BROKEN（终态）。
     */
    @Transactional
    public StoredResponse inspectSeal(String actorId, String evidenceKey,
                                      SealInspectionRequest request, String requestHash) {
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
        requireCustodian(evidence, actorId);
        if (evidence.status() == EvidenceStatus.QUARANTINED) {
            throw ApiException.conflict("证物隔离待复核，禁止独立封条核验: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待接收期间禁止封条核验: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("借出期间禁止独立封条核验: " + evidenceKey);
        }
        LocalDateTime now = LocalDateTime.now();
        inspectionRepository.insert(evidenceKey, actorId, request.passed(), request.note(), now);
        if (!request.passed() && evidence.status() != EvidenceStatus.SEAL_BROKEN) {
            evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.SEAL_BROKEN, now);
        }
        List<SealInspection> inspections = inspectionRepository.findByEvidenceKey(evidenceKey);
        SealInspection created = inspections.get(inspections.size() - 1);
        return record(request.commandKey(), OP_SEAL_INSPECTION, actorId, requestHash,
                200, toView(created));
    }

    /**
     * 限时借出：仅当前保管人，证物须为 SEALED；进入 BORROWED，保管人不变。
     * 借用人与保管人不同；dueAt 为 UTC 时刻，须晚于当前且不超过 72 小时。
     * 待接收交接（TRANSFER_PENDING）、隔离待复核（QUARANTINED）或封条异常（SEAL_BROKEN）证物不能借出。
     */
    @Transactional
    public StoredResponse borrow(String actorId, String evidenceKey,
                                 LoanCreateRequest request, String requestHash) {
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
        if (request.borrowerId().equals(actorId)) {
            throw ApiException.badRequest("借用人不能与当前保管人相同");
        }
        requireCustodian(evidence, actorId);
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止借出: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.QUARANTINED) {
            throw ApiException.conflict("证物隔离待复核，复核放行前禁止借出: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待接收交接期间禁止借出: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("证物已存在未归还借出: " + evidenceKey);
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!request.dueAt().isAfter(nowUtc)) {
            throw ApiException.badRequest("应还时刻须晚于服务端当前时刻");
        }
        if (request.dueAt().isAfter(nowUtc.plusHours(MAX_LOAN_HOURS))) {
            throw ApiException.badRequest("借出期限不得超过 " + MAX_LOAN_HOURS + " 小时");
        }
        if (loanRepository.findByLoanKey(request.loanKey()).isPresent()) {
            throw ApiException.conflict("借出键已存在: " + request.loanKey());
        }
        loanRepository.insert(request.loanKey(), evidenceKey, actorId, request.borrowerId(),
                request.purpose(), nowUtc, request.dueAt(), LocalDateTime.now());
        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.BORROWED, LocalDateTime.now());
        LoanRecord loan = loanRepository.findByLoanKey(request.loanKey()).orElseThrow();
        return record(request.commandKey(), OP_LOAN_BORROW, actorId, requestHash,
                200, toView(loan));
    }

    /**
     * 确认归还：仅借出时的保管人，必须指定本次 loanKey、封条是否完好及非空说明。
     * 借用人不能代为确认。完好回到 SEALED，异常进入 SEAL_BROKEN；
     * 借出归还结果、封条核验记录与证物状态在同一事务落库，历史不可覆盖。
     * 证物所属容器处于异常隔离待复核时，复核前禁止归还（409）。
     */
    @Transactional
    public StoredResponse returnLoan(String actorId, String evidenceKey,
                                     LoanReturnRequest request, String requestHash) {
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
        LoanRecord loan = loanRepository.findByLoanKey(request.loanKey())
                .orElseThrow(() -> ApiException.notFound("借出记录不存在: " + request.loanKey()));
        if (!loan.evidenceKey().equals(evidenceKey)) {
            throw ApiException.notFound("借出记录不属于该证物: " + request.loanKey());
        }
        if (loan.borrowerId().equals(actorId)) {
            throw ApiException.conflict("借用人不能代为确认归还: " + actorId);
        }
        if (!loan.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅借出时的保管人可确认归还: " + actorId);
        }
        if (evidence.containerKey() != null) {
            containerRepository.findByKey(evidence.containerKey()).ifPresent(container -> {
                if (container.status() == ContainerStatus.QUARANTINED) {
                    throw ApiException.conflict(
                            "所属容器封条异常隔离待复核，复核放行前禁止归还: " + evidenceKey);
                }
            });
        }
        if (loan.status() != LoanStatus.ACTIVE || evidence.status() != EvidenceStatus.BORROWED) {
            throw ApiException.conflict("借出已归还，旧借出键不得再次结束借出: " + request.loanKey());
        }
        boolean sameActiveLoan = loanRepository.findActiveByEvidenceKey(evidenceKey)
                .map(active -> active.id().equals(loan.id())).orElse(false);
        if (!sameActiveLoan) {
            throw ApiException.conflict("借出记录与证物当前未归还借出不一致: " + request.loanKey());
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!loanRepository.completeReturn(loan.id(), request.sealIntact(), request.note(), nowUtc)) {
            throw ApiException.conflict("借出已被并发归还: " + request.loanKey());
        }
        inspectionRepository.insert(evidenceKey, actorId, request.sealIntact(), request.note(),
                LocalDateTime.now());
        EvidenceStatus restored = request.sealIntact()
                ? EvidenceStatus.SEALED : EvidenceStatus.SEAL_BROKEN;
        evidenceRepository.updateStatus(evidenceKey, restored, LocalDateTime.now());
        LoanRecord returned = loanRepository.findByLoanKey(request.loanKey()).orElseThrow();
        return record(request.commandKey(), OP_LOAN_RETURN, actorId, requestHash,
                200, toView(returned));
    }

    /**
     * 登记容器：初始封条版本 1、状态 ACTIVE，保管人为操作人。containerKey 全局唯一。
     */
    @Transactional
    public StoredResponse createContainer(String actorId, ContainerCreateRequest request,
                                          String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (containerRepository.findByKey(request.containerKey()).isPresent()) {
            throw ApiException.conflict("容器已存在: " + request.containerKey());
        }
        LocalDateTime now = LocalDateTime.now();
        containerRepository.insert(request.containerKey(), request.location(), actorId, now);
        Container container = containerRepository.findByKey(request.containerKey()).orElseThrow();
        return record(request.commandKey(), OP_CONTAINER_CREATE, actorId, requestHash,
                201, toView(container));
    }

    /**
     * 更换封条：仅容器登记保管人，容器须 ACTIVE；封条版本递增 1。
     * 容器隔离待复核期间禁止换封条；巡检见证版本必须与新版本一致。
     */
    @Transactional
    public StoredResponse replaceSeal(String actorId, String containerKey,
                                      SealReplaceRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Container container = lockContainer(containerKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!container.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是容器登记保管人: " + actorId);
        }
        if (container.status() == ContainerStatus.QUARANTINED) {
            throw ApiException.conflict("容器隔离待复核，复核放行前禁止更换封条: " + containerKey);
        }
        LocalDateTime now = LocalDateTime.now();
        containerRepository.bumpSealVersion(containerKey, now);
        Container updated = containerRepository.findByKey(containerKey).orElseThrow();
        return record(request.commandKey(), OP_SEAL_REPLACE, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 证物放入容器：仅证物当前保管人，证物须 SEALED，容器须 ACTIVE；追加一条 PLACED 位置轨迹。
     * 加锁顺序统一为先容器后证物，与异常巡检隔离一致，避免交叉死锁。
     */
    @Transactional
    public StoredResponse placeIntoContainer(String actorId, String evidenceKey,
                                             ContainerPlaceRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Container container = lockContainer(request.containerKey());
        Evidence evidence = lockEvidence(evidenceKey);
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (container.status() == ContainerStatus.QUARANTINED) {
            throw ApiException.conflict("容器隔离待复核，禁止放入证物: " + request.containerKey());
        }
        requireCustodian(evidence, actorId);
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("仅 SEALED 证物可放入容器，当前状态: " + evidence.status());
        }
        LocalDateTime now = LocalDateTime.now();
        evidenceRepository.updateContainer(evidenceKey, request.containerKey(), now);
        Evidence updated = evidenceRepository.findByKey(evidenceKey).orElseThrow();
        appendLocation(updated, LocationReason.PLACED, null, now);
        return record(request.commandKey(), OP_CONTAINER_PLACE, actorId, requestHash,
                200, toView(updated));
    }

    /**
     * 容器巡检见证：两名不同保管人凭同一 requestId 确认同一封条版本与同一结论。
     * 第一见证人建单（AWAITING_WITNESS，不改变任何证物状态）；第二见证人满足双人确认时：
     * 版本或结论不一致则巡检作废（CONFLICTED）且不改变证物状态；
     * 一致报异常则单事务将巡检时仍在箱内的全部证物一次隔离并冻结原位置快照。
     * requestId 重放：见证人本人同参重放返回首次完成响应（首次隔离清单），
     * 换封条结论/版本或由第三人提交均返回 409。
     */
    @Transactional
    public StoredResponse inspectContainer(String actorId, String containerKey,
                                           ContainerInspectionRequest request) {
        LocalDateTime now = LocalDateTime.now();
        // 先锁容器：换封条、复核放行与所有巡检确认在容器行上串行。
        Container container = lockContainer(containerKey);
        ContainerInspection inspection = containerInspectionRepository
                .findByRequestIdForUpdate(request.requestId()).orElse(null);

        if (inspection == null) {
            if (container.status() == ContainerStatus.QUARANTINED) {
                throw ApiException.conflict(
                        "容器隔离待复核，复核放行前禁止新巡检: " + containerKey);
            }
            if (!request.witnessedVersion().equals(container.sealVersion())) {
                throw ApiException.conflict("见证封条版本与容器当前版本不一致: "
                        + request.witnessedVersion() + " != " + container.sealVersion());
            }
            try {
                containerInspectionRepository.insert(request.requestId(), containerKey,
                        request.witnessedVersion(), request.sealResult(), now);
            } catch (DuplicateKeyException concurrent) {
                // 两名见证人并发首提同一 requestId：先提交事务已释放容器行锁，持锁重读后按第二见证人处理。
                inspection = containerInspectionRepository
                        .findByRequestIdForUpdate(request.requestId()).orElseThrow();
                return handleSecondWitness(container, inspection, actorId, request,
                        LocalDateTime.now());
            }
            ContainerInspection created = containerInspectionRepository
                    .findByRequestId(request.requestId()).orElseThrow();
            containerInspectionRepository.insertWitness(created.id(), actorId,
                    request.sealResult(), request.note(), now);
            return new StoredResponse(200, toJson(toInspectionView(created.id())));
        }

        return handleSecondWitness(container, inspection, actorId, request, now);
    }

    /**
     * 第二（及之后）见证人处理：已完成巡检仅允许原见证人同版本同结论重放首次结果；
     * 仅一人确认时，不同保管人同版本同结论完成双人确认，版本或结论分歧则作废且不改证物状态。
     */
    private StoredResponse handleSecondWitness(Container container, ContainerInspection inspection,
                                               String actorId, ContainerInspectionRequest request,
                                               LocalDateTime now) {
        if (!inspection.containerKey().equals(container.containerKey())) {
            throw ApiException.conflict("巡检请求属于其他容器: " + inspection.containerKey());
        }

        List<InspectionWitness> witnesses = containerInspectionRepository.findWitnesses(inspection.id());
        boolean sameWitness = witnesses.stream().anyMatch(w -> w.witnessId().equals(actorId));

        if (inspection.status() != InspectionStatus.AWAITING_WITNESS) {
            // 已完成巡检的重放：仅原见证人同版本同结论重放首次结果；换封条结果等一律 409。
            boolean identicalReplay = sameWitness
                    && request.witnessedVersion().equals(inspection.containerSealVersion())
                    && request.sealResult() == inspection.sealResult();
            if (!identicalReplay) {
                throw ApiException.conflict(
                        "巡检请求已完成，封条结论或见证版本不一致时禁止重放: " + request.requestId());
            }
            return new StoredResponse(200, inspection.completedResponse());
        }

        if (sameWitness) {
            throw ApiException.conflict("同一保管人不能作为两名见证人: " + actorId);
        }
        if (container.status() == ContainerStatus.QUARANTINED) {
            // 本单建单后、第二人到达前，容器已被另一张巡检单双人确认隔离：
            // 本单作废，不重复隔离、不重复冻结清单，证物状态不再变动。
            containerInspectionRepository.insertWitness(inspection.id(), actorId,
                    request.sealResult(), request.note(), now);
            containerInspectionRepository.markConflicted(inspection.id(), now);
            return new StoredResponse(200, toJson(toInspectionView(inspection.id())));
        }
        containerInspectionRepository.insertWitness(inspection.id(), actorId,
                request.sealResult(), request.note(), now);

        boolean sameVersion = request.witnessedVersion().equals(inspection.containerSealVersion())
                && request.witnessedVersion().equals(container.sealVersion());
        boolean sameResult = request.sealResult() == inspection.sealResult();
        if (!sameVersion || !sameResult) {
            // 版本不一致或结论分歧：巡检作废，不改变任何证物状态。
            containerInspectionRepository.markConflicted(inspection.id(), now);
            return new StoredResponse(200, toJson(toInspectionView(inspection.id())));
        }

        if (request.sealResult() == SealResult.ANOMALY) {
            quarantineContainer(container, inspection.id(), now);
            containerInspectionRepository.complete(inspection.id(),
                    InspectionStatus.CONFIRMED_ANOMALY, null, now);
            String json = toJson(toInspectionView(inspection.id()));
            containerInspectionRepository.complete(inspection.id(),
                    InspectionStatus.CONFIRMED_ANOMALY, json, now);
            return new StoredResponse(200, json);
        }

        freezeItems(container, inspection.id(), now);
        containerInspectionRepository.complete(inspection.id(),
                InspectionStatus.CONFIRMED_INTACT, null, now);
        String json = toJson(toInspectionView(inspection.id()));
        containerInspectionRepository.complete(inspection.id(),
                InspectionStatus.CONFIRMED_INTACT, json, now);
        return new StoredResponse(200, json);
    }

    /**
     * 异常双人确认后的隔离动作：锁箱内全部证物行（按证物键固定顺序），冻结每件快照，
     * 仍在箱内的证物一次转为 QUARANTINED，待接收交接同步作废，容器转为 QUARANTINED。
     * 借出在外（BORROWED）的证物物理不在箱内，标记 NOT_PRESENT 且不改变其状态。
     */
    private void quarantineContainer(Container container, long inspectionId, LocalDateTime now) {
        List<Evidence> items = evidenceRepository.findByContainerKeyForUpdate(container.containerKey());
        for (Evidence evidence : items) {
            Long pendingTransferId = null;
            if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
                pendingTransferId = transferRepository
                        .findPendingByEvidenceKey(evidence.evidenceKey())
                        .map(TransferRecord::id).orElse(null);
            }
            if (evidence.status() == EvidenceStatus.BORROWED) {
                inspectionItemRepository.insert(inspectionId, evidence.evidenceKey(),
                        evidence.containerKey(), evidence.custodianId(), evidence.status(),
                        null, ItemDisposition.NOT_PRESENT, now);
                continue;
            }
            inspectionItemRepository.insert(inspectionId, evidence.evidenceKey(),
                    evidence.containerKey(), evidence.custodianId(), evidence.status(),
                    pendingTransferId, ItemDisposition.QUARANTINED, now);
            if (evidence.status() == EvidenceStatus.TRANSFER_PENDING && pendingTransferId != null) {
                // 隔离使待接收交接无法继续：同步作废并在快照中保留原交接行，历史不删除。
                transferRepository.decide(pendingTransferId, TransferStatus.CANCELLED, now);
            }
            if (evidence.status() != EvidenceStatus.SEAL_BROKEN) {
                evidenceRepository.updateStatus(evidence.evidenceKey(),
                        EvidenceStatus.QUARANTINED, now);
            }
            appendLocation(evidence, LocationReason.INSPECTION_QUARANTINE, inspectionId, now);
        }
        containerRepository.updateStatus(container.containerKey(), ContainerStatus.QUARANTINED, now);
    }

    /**
     * 封条完好双人确认时冻结箱内清单但不隔离：在箱证物标记 UNQUARANTINED，借出在外标记 NOT_PRESENT。
     */
    private void freezeItems(Container container, long inspectionId, LocalDateTime now) {
        List<Evidence> items = evidenceRepository.findByContainerKey(container.containerKey());
        for (Evidence evidence : items) {
            ItemDisposition disposition = evidence.status() == EvidenceStatus.BORROWED
                    ? ItemDisposition.NOT_PRESENT : ItemDisposition.UNQUARANTINED;
            inspectionItemRepository.insert(inspectionId, evidence.evidenceKey(),
                    evidence.containerKey(), evidence.custodianId(), evidence.status(),
                    null, disposition, now);
        }
    }

    /**
     * 复核放行：仅 CONFIRMED_ANOMALY 巡检可复核，单事务把当时隔离的箱内证物恢复 SEALED，
     * 容器恢复 ACTIVE；巡检、隔离快照、位置轨迹与带时刻复核结论全部保留，历史不改写。
     */
    @Transactional
    public StoredResponse reviewInspection(String actorId, String requestId,
                                           InspectionReviewRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        ContainerInspection inspection = containerInspectionRepository
                .findByRequestIdForUpdate(requestId)
                .orElseThrow(() -> ApiException.notFound("巡检不存在: " + requestId));
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (inspection.status() != InspectionStatus.CONFIRMED_ANOMALY) {
            throw ApiException.conflict("仅双人确认封条异常的巡检可复核，当前状态: " + inspection.status());
        }
        if (inspectionReviewRepository.findByInspectionId(inspection.id()).isPresent()) {
            throw ApiException.conflict("巡检已复核: " + requestId);
        }
        Container container = lockContainer(inspection.containerKey());

        LocalDateTime now = LocalDateTime.now();
        List<InspectionItem> items = inspectionItemRepository.findByInspectionId(inspection.id());
        List<String> quarantinedKeys = items.stream()
                .filter(item -> item.disposition() == ItemDisposition.QUARANTINED)
                .map(InspectionItem::evidenceKey).toList();
        // 一次按固定顺序锁定全部待放行证物，与隔离/交接并发互不撕裂。
        List<Evidence> locked = evidenceRepository.findByKeysForUpdate(quarantinedKeys);
        for (InspectionItem item : items) {
            if (item.disposition() != ItemDisposition.QUARANTINED) {
                continue;
            }
            Evidence evidence = locked.stream()
                    .filter(e -> e.evidenceKey().equals(item.evidenceKey())).findFirst()
                    .orElseThrow(() -> ApiException.notFound("隔离清单中的证物不存在: " + item.evidenceKey()));
            if (evidence.status() != EvidenceStatus.QUARANTINED) {
                // 隔离后证物自身封条被判定异常（SEAL_BROKEN 终态）等情形：放行不得将其冲回 SEALED，
                // 该清单项保留 QUARANTINED 处置，仅放行其余仍处隔离的证物。
                continue;
            }
            evidenceRepository.updateStatus(evidence.evidenceKey(), EvidenceStatus.SEALED, now);
            appendLocation(evidence, LocationReason.REVIEW_RELEASE, inspection.id(), now);
            if (!inspectionItemRepository.markReleased(item.id(), now)) {
                throw ApiException.conflict("清单项已被并发放行: " + item.evidenceKey());
            }
        }
        inspectionReviewRepository.insert(inspection.id(), actorId,
                InspectionConclusion.RELEASED, request.note(), now);
        containerRepository.updateStatus(container.containerKey(), ContainerStatus.ACTIVE, now);
        return record(request.commandKey(), OP_INSPECTION_REVIEW, actorId, requestHash,
                200, toInspectionView(inspection.id()));
    }

    /**
     * 按借用人查询未归还借出及逾期记录（逾期仅为查询时刻标识，不改变任何状态）。
     */
    @Transactional(readOnly = true)
    public List<LoanView> listActiveLoansByBorrower(String borrowerId) {
        return loanRepository.findByBorrower(borrowerId).stream()
                .filter(loan -> loan.status() == LoanStatus.ACTIVE)
                .map(this::toView)
                .toList();
    }

    /**
     * 查询当前操作人可交接的证物（本人保管且 SEALED）。
     */
    @Transactional(readOnly = true)
    public List<EvidenceView> listTransferable(String actorId) {
        return evidenceRepository.findTransferable(actorId).stream().map(this::toView).toList();
    }

    /**
     * 查询容器详情。
     */
    @Transactional(readOnly = true)
    public ContainerView containerView(String containerKey) {
        return toView(containerRepository.findByKey(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey)));
    }

    /**
     * 按 requestId 查询巡检详情：巡检人、封条版本与结论、当时箱内清单（每件隔离状态）与复核结论。
     */
    @Transactional(readOnly = true)
    public ContainerInspectionView inspectionView(String requestId) {
        ContainerInspection inspection = containerInspectionRepository.findByRequestId(requestId)
                .orElseThrow(() -> ApiException.notFound("巡检不存在: " + requestId));
        return toInspectionView(inspection.id());
    }

    /**
     * 查询完整保管链：证物当前状态 + 全部位置轨迹 + 全部交接记录 + 全部借出记录 + 全部核验记录。
     */
    @Transactional(readOnly = true)
    public CustodyChainView custodyChain(String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        List<LocationView> locations = locationSnapshotRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
        List<TransferView> transfers = transferRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
        List<LoanView> loans = loanRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
        List<InspectionView> inspections = inspectionRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
        return new CustodyChainView(toView(evidence), locations, transfers, loans, inspections);
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private Container lockContainer(String containerKey) {
        return containerRepository.findByKeyForUpdate(containerKey)
                .orElseThrow(() -> ApiException.notFound("容器不存在: " + containerKey));
    }

    private void requireSealIntact(Evidence evidence) {
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止交接相关操作: " + evidence.evidenceKey());
        }
    }

    private void requireNotQuarantined(Evidence evidence) {
        if (evidence.status() == EvidenceStatus.QUARANTINED) {
            throw ApiException.conflict("证物隔离待复核，复核放行前禁止交接: " + evidence.evidenceKey());
        }
    }

    private void requireCustodian(Evidence evidence, String actorId) {
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId);
        }
    }

    private TransferRecord requirePending(Evidence evidence, String evidenceKey) {
        if (evidence.status() != EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("证物不存在待接收交接: " + evidenceKey);
        }
        return transferRepository.findPendingByEvidenceKey(evidenceKey)
                .orElseThrow(() -> ApiException.conflict("证物不存在待接收交接: " + evidenceKey));
    }

    private void appendLocation(Evidence evidence, LocationReason reason, Long inspectionId,
                                LocalDateTime now) {
        locationSnapshotRepository.insert(evidence.evidenceKey(), evidence.containerKey(),
                evidence.custodianId(), reason, inspectionId, now);
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

    private EvidenceView toView(Evidence evidence) {
        return new EvidenceView(evidence.evidenceKey(), evidence.caseKey(), evidence.category(),
                evidence.sealNo(), evidence.custodianId(), evidence.containerKey(),
                evidence.status(), evidence.createdAt(), evidence.updatedAt());
    }

    private ContainerView toView(Container container) {
        return new ContainerView(container.containerKey(), container.location(),
                container.custodianId(), container.sealVersion(), container.status(),
                container.createdAt(), container.updatedAt());
    }

    private TransferView toView(TransferRecord record) {
        return new TransferView(record.evidenceKey(), record.fromCustodian(), record.toCustodian(),
                record.status(), record.initiatedBy(), record.createdAt(), record.decidedAt());
    }

    private InspectionView toView(SealInspection inspection) {
        return new InspectionView(inspection.evidenceKey(), inspection.inspectorId(),
                inspection.passed(), inspection.note(), inspection.createdAt());
    }

    private LoanView toView(LoanRecord loan) {
        // 达到应还时刻即逾期：当前 UTC 时刻不早于 dueAt 且未归还时 overdue=true。
        boolean overdue = loan.status() == LoanStatus.ACTIVE
                && !clock.nowUtc().isBefore(loan.dueAt());
        return new LoanView(loan.loanKey(), loan.evidenceKey(), loan.custodianId(),
                loan.borrowerId(), loan.purpose(), loan.loanAt(), loan.dueAt(), loan.status(),
                overdue, loan.sealPassed(), loan.returnNote(), loan.returnedAt());
    }

    private LocationView toView(LocationSnapshot snapshot) {
        return new LocationView(snapshot.containerKey(), snapshot.custodianId(),
                snapshot.reason(), snapshot.inspectionId(), snapshot.createdAt());
    }

    private InspectionWitnessView toWitnessView(InspectionWitness witness) {
        return new InspectionWitnessView(witness.witnessId(), witness.sealResult(),
                witness.note(), witness.createdAt());
    }

    private InspectionItemView toItemView(InspectionItem item) {
        return new InspectionItemView(item.evidenceKey(), item.snapshotContainerKey(),
                item.snapshotCustodianId(), item.snapshotStatus(), item.disposition(),
                item.releasedAt());
    }

    private InspectionReviewView toReviewView(InspectionReview review) {
        return new InspectionReviewView(review.reviewerId(), review.conclusion(),
                review.note(), review.createdAt());
    }

    /**
     * 组装巡检详情视图；清单在双人确认完成后冻结，复核结论可空。
     */
    private ContainerInspectionView toInspectionView(long inspectionId) {
        ContainerInspection inspection = containerInspectionRepository.findById(inspectionId)
                .orElseThrow(() -> ApiException.notFound("巡检不存在: " + inspectionId));
        List<InspectionWitnessView> witnesses = containerInspectionRepository
                .findWitnesses(inspectionId).stream().map(this::toWitnessView).toList();
        // 仅一人确认时清单尚未冻结；分歧作废不冻结清单；完成（完好/异常）时展示当时箱内清单。
        List<InspectionItemView> items = inspection.status() == InspectionStatus.AWAITING_WITNESS
                ? List.of()
                : inspectionItemRepository.findByInspectionId(inspectionId).stream()
                        .map(this::toItemView).toList();
        InspectionReviewView review = inspectionReviewRepository
                .findByInspectionId(inspectionId).map(this::toReviewView).orElse(null);
        return new ContainerInspectionView(inspection.requestId(), inspection.containerKey(),
                inspection.containerSealVersion(), inspection.sealResult(), inspection.status(),
                witnesses, items, review, inspection.createdAt(), inspection.completedAt());
    }
}
