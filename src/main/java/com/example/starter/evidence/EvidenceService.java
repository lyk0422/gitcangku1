package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.BorrowerFreezeView;
import com.example.starter.evidence.dto.BorrowerUnfreezeRequest;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.CustodyChainView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.InspectionView;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LoanReclaimRequest;
import com.example.starter.evidence.dto.LoanReturnRequest;
import com.example.starter.evidence.dto.LoanView;
import com.example.starter.evidence.dto.ReclaimView;
import com.example.starter.evidence.dto.SealInspectionRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import com.example.starter.evidence.dto.TransferView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 证物封存交接核心服务。所有写操作在单事务内完成：
 * 先锁定证物行（SELECT ... FOR UPDATE），再校验状态机并落库，最后写入幂等日志。
 * 交接记录与核验记录只追加；证物业务字段入库后不可修改。
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
    static final String OP_LOAN_RECLAIM = "LOAN_RECLAIM";
    static final String OP_BORROWER_UNFREEZE = "BORROWER_UNFREEZE";

    /**
     * 借出最长期限：72 小时。
     */
    static final long MAX_LOAN_HOURS = 72;

    /**
     * 借出人冻结阈值：有效追缴计数（自最近一次解冻以来）达到该值即自动冻结借出权限。
     */
    static final int FREEZE_THRESHOLD = 2;

    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final SealInspectionRepository inspectionRepository;
    private final LoanRecordRepository loanRepository;
    private final ReclaimRecordRepository reclaimRepository;
    private final UnfreezeRecordRepository unfreezeRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           TransferRecordRepository transferRepository,
                           SealInspectionRepository inspectionRepository,
                           LoanRecordRepository loanRepository,
                           ReclaimRecordRepository reclaimRepository,
                           UnfreezeRecordRepository unfreezeRepository,
                           CommandLogRepository commandLogRepository,
                           ObjectMapper objectMapper,
                           EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.loanRepository = loanRepository;
        this.reclaimRepository = reclaimRepository;
        this.unfreezeRepository = unfreezeRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 证物入库：初始状态 SEALED，保管人为操作人。evidenceKey 全局唯一。
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
        requireCustodian(evidence, actorId);
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("证物已存在待接收交接: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("借出期间禁止发起交接: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.PENDING_INSPECTION) {
            throw ApiException.conflict("追缴回库待核验，完成封条核验前禁止发起交接: " + evidenceKey);
        }
        LocalDateTime now = LocalDateTime.now();
        transferRepository.insert(evidenceKey, actorId, request.toCustodian(), now);
        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.TRANSFER_PENDING, now);
        TransferRecord pending = transferRepository.findPendingByEvidenceKey(evidenceKey).orElseThrow();
        return record(request.commandKey(), OP_TRANSFER_INITIATE, actorId, requestHash,
                200, toView(pending));
    }

    /**
     * 接受交接：仅指定接收人；保管人原子切换为接收人并回到 SEALED。
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
     * 封条核验：仅当前保管人，待接收期间禁止核验。
     * 通过仅追加不可变记录；失败使证物进入 SEAL_BROKEN（终态）。
     * 追缴回库待核验（PENDING_INSPECTION）的证物经核验通过回到 SEALED，失败进入 SEAL_BROKEN。
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
        if (request.passed() && evidence.status() == EvidenceStatus.PENDING_INSPECTION) {
            evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.SEALED, now);
        }
        List<SealInspection> inspections = inspectionRepository.findByEvidenceKey(evidenceKey);
        SealInspection created = inspections.get(inspections.size() - 1);
        return record(request.commandKey(), OP_SEAL_INSPECTION, actorId, requestHash,
                200, toView(created));
    }

    /**
     * 限时借出：仅当前保管人，证物须为 SEALED；进入 BORROWED，保管人不变。
     * 借用人与保管人不同；dueAt 为 UTC 时刻，须晚于当前且不超过 72 小时。
     * 待接收交接（TRANSFER_PENDING）或封条异常（SEAL_BROKEN）证物不能借出。
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
        requireNotFrozen(request.borrowerId());
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止借出: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.PENDING_INSPECTION) {
            throw ApiException.conflict("追缴回库待核验，完成封条核验前禁止借出: " + evidenceKey);
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
        if (loan.status() == LoanStatus.RECLAIMED) {
            throw ApiException.conflict("借出已被追缴，不得再确认归还: " + request.loanKey());
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
     * 查询完整保管链：证物当前状态 + 全部交接记录 + 全部借出记录 + 全部核验记录。
     */
    @Transactional(readOnly = true)
    public CustodyChainView custodyChain(String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        List<TransferView> transfers = transferRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
        List<LoanView> loans = loanRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
        List<InspectionView> inspections = inspectionRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
        return new CustodyChainView(toView(evidence), transfers, loans, inspections);
    }

    /**
     * 逾期追缴：仅借出时的保管人，借出须处于逾期（ACTIVE 且当前 UTC 时刻不早于应还时刻）。
     * 同一事务内：借出记录转 RECLAIMED 终态、证物转 PENDING_INSPECTION（在库待核验）、
     * 写入不可变追缴记录（固化原应还时刻、逾期分钟数与说明）。
     * 重复提交按 reclaimKey 幂等返回首次结果；未逾期返回 422 并给出到期时刻；
     * 已正常归还返回 409；并发下归还先提交则本操作返回 422。
     */
    @Transactional
    public StoredResponse reclaim(String actorId, String evidenceKey,
                                  LoanReclaimRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.reclaimKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 锁前读：区分“已正常归还”（409）与并发下“归还先提交”（持锁后才发现，422）。
        Optional<LoanRecord> beforeLock = loanRepository.findByLoanKey(request.loanKey());
        if (beforeLock.isPresent() && beforeLock.get().status() == LoanStatus.RETURNED) {
            throw ApiException.conflict("借出已正常归还，不得追缴: " + request.loanKey());
        }
        Evidence evidence = lockEvidence(evidenceKey);
        // 并发下本事务可能在证物行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.reclaimKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        LoanRecord loan = loanRepository.findByLoanKey(request.loanKey())
                .orElseThrow(() -> ApiException.notFound("借出记录不存在: " + request.loanKey()));
        if (!loan.evidenceKey().equals(evidenceKey)) {
            throw ApiException.notFound("借出记录不属于该证物: " + request.loanKey());
        }
        if (!loan.custodianId().equals(actorId) || !evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅借出时的保管人可提交追缴: " + actorId);
        }
        if (loan.status() == LoanStatus.RECLAIMED) {
            throw ApiException.conflict("借出已被追缴，同一借出记录只能追缴一次: " + request.loanKey());
        }
        if (loan.status() == LoanStatus.RETURNED) {
            // 锁前读时仍为 ACTIVE，持锁后发现已归还：并发下归还先提交。
            throw ApiException.unprocessable("借出已被并发归还，不再处于逾期未归还状态: " + request.loanKey());
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (nowUtc.isBefore(loan.dueAt())) {
            throw ApiException.unprocessable("借出未逾期，不得追缴，应还时刻（UTC）: " + loan.dueAt());
        }
        if (!loanRepository.completeReclaim(loan.id())) {
            throw ApiException.unprocessable("借出已被并发归还，不再处于逾期未归还状态: " + request.loanKey());
        }
        long overdueMinutes = Duration.between(loan.dueAt(), nowUtc).toMinutes();
        LocalDateTime now = LocalDateTime.now();
        reclaimRepository.insert(request.reclaimKey(), loan.loanKey(), evidenceKey, loan.borrowerId(),
                actorId, loan.dueAt(), overdueMinutes, request.note(), now);
        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.PENDING_INSPECTION, now);
        ReclaimRecord reclaim = reclaimRepository.findByReclaimKey(request.reclaimKey()).orElseThrow();
        return record(request.reclaimKey(), OP_LOAN_RECLAIM, actorId, requestHash,
                200, toView(reclaim));
    }

    /**
     * 解冻借出人：须由另一名保管人（非借出人本人）提交说明，写入不可变解冻记录。
     * 解冻后追缴计数从零重新累计，历史追缴记录保留。
     */
    @Transactional
    public StoredResponse unfreezeBorrower(String actorId, String borrowerId,
                                           BorrowerUnfreezeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (actorId.equals(borrowerId)) {
            throw ApiException.conflict("解冻须由另一名保管人提交，借出人不能自行解冻: " + actorId);
        }
        int total = reclaimRepository.countByBorrower(borrowerId);
        int effective = effectiveReclaimCount(borrowerId, total);
        if (effective < FREEZE_THRESHOLD) {
            throw ApiException.conflict("借出人未处于冻结状态: " + borrowerId);
        }
        unfreezeRepository.insert(borrowerId, actorId, request.note(), total, LocalDateTime.now());
        return record(request.commandKey(), OP_BORROWER_UNFREEZE, actorId, requestHash,
                200, freezeView(borrowerId));
    }

    /**
     * 逾期清单：当前 UTC 时刻已逾期（ACTIVE 且到期时刻已过）的全部借出，按应还时刻升序。
     */
    @Transactional(readOnly = true)
    public List<LoanView> listOverdueLoans() {
        return loanRepository.findOverdue(clock.nowUtc()).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 追缴记录查询：borrowerId 为空时返回全部（按发生顺序）。
     */
    @Transactional(readOnly = true)
    public List<ReclaimView> listReclaims(String borrowerId) {
        List<ReclaimRecord> records = (borrowerId == null || borrowerId.isBlank())
                ? reclaimRepository.findAll()
                : reclaimRepository.findByBorrower(borrowerId);
        return records.stream().map(this::toView).toList();
    }

    /**
     * 借出人冻结状态查询：是否冻结、有效追缴计数（自最近一次解冻以来）与历史总次数。
     */
    @Transactional(readOnly = true)
    public BorrowerFreezeView borrowerFreezeStatus(String borrowerId) {
        return freezeView(borrowerId);
    }

    private BorrowerFreezeView freezeView(String borrowerId) {
        int total = reclaimRepository.countByBorrower(borrowerId);
        int effective = effectiveReclaimCount(borrowerId, total);
        Optional<UnfreezeRecord> latest = unfreezeRepository.findLatestByBorrower(borrowerId);
        return new BorrowerFreezeView(borrowerId, effective >= FREEZE_THRESHOLD, effective, total,
                FREEZE_THRESHOLD,
                latest.map(UnfreezeRecord::createdAt).orElse(null),
                latest.map(UnfreezeRecord::unfrozenBy).orElse(null));
    }

    private int effectiveReclaimCount(String borrowerId, int totalReclaimCount) {
        int baseline = unfreezeRepository.findLatestByBorrower(borrowerId)
                .map(UnfreezeRecord::reclaimCountAtUnfreeze)
                .orElse(0);
        return totalReclaimCount - baseline;
    }

    /**
     * 借出人冻结检查：有效追缴计数达到阈值即冻结，冻结期间新借出申请返回 403 并给出原因与次数。
     */
    private void requireNotFrozen(String borrowerId) {
        int total = reclaimRepository.countByBorrower(borrowerId);
        int effective = effectiveReclaimCount(borrowerId, total);
        if (effective >= FREEZE_THRESHOLD) {
            throw ApiException.forbidden("借出人已被冻结：累计被追缴 " + effective
                    + " 次（阈值 " + FREEZE_THRESHOLD + " 次），须由保管人解冻后方可再次借出: " + borrowerId);
        }
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void requireSealIntact(Evidence evidence) {
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止交接相关操作: " + evidence.evidenceKey());
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
                evidence.sealNo(), evidence.custodianId(), evidence.status(),
                evidence.createdAt(), evidence.updatedAt());
    }

    private TransferView toView(TransferRecord record) {
        return new TransferView(record.evidenceKey(), record.fromCustodian(), record.toCustodian(),
                record.status(), record.initiatedBy(), record.createdAt(), record.decidedAt());
    }

    private InspectionView toView(SealInspection inspection) {
        return new InspectionView(inspection.evidenceKey(), inspection.inspectorId(),
                inspection.passed(), inspection.note(), inspection.createdAt());
    }

    private ReclaimView toView(ReclaimRecord reclaim) {
        return new ReclaimView(reclaim.reclaimKey(), reclaim.loanKey(), reclaim.evidenceKey(),
                reclaim.borrowerId(), reclaim.custodianId(), reclaim.dueAt(),
                reclaim.overdueMinutes(), reclaim.note(), reclaim.createdAt());
    }

    private LoanView toView(LoanRecord loan) {
        // 达到应还时刻即逾期：当前 UTC 时刻不早于 dueAt 且未归还时 overdue=true。
        boolean overdue = loan.status() == LoanStatus.ACTIVE
                && !clock.nowUtc().isBefore(loan.dueAt());
        LoanStatus effectiveStatus = overdue ? LoanStatus.OVERDUE : loan.status();
        return new LoanView(loan.loanKey(), loan.evidenceKey(), loan.custodianId(),
                loan.borrowerId(), loan.purpose(), loan.loanAt(), loan.dueAt(), loan.status(),
                effectiveStatus, overdue, loan.sealPassed(), loan.returnNote(), loan.returnedAt());
    }
}
