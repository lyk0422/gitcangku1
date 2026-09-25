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
import com.example.starter.evidence.dto.UnfreezeView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
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
     * 借出人冻结阈值：累计被追缴达到该次数即自动冻结借出权限。
     */
    static final int FREEZE_RECLAIM_THRESHOLD = 2;

    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final SealInspectionRepository inspectionRepository;
    private final LoanRecordRepository loanRepository;
    private final ReclaimRecordRepository reclaimRepository;
    private final BorrowerFreezeRepository freezeRepository;
    private final UnfreezeRecordRepository unfreezeRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           TransferRecordRepository transferRepository,
                           SealInspectionRepository inspectionRepository,
                           LoanRecordRepository loanRepository,
                           ReclaimRecordRepository reclaimRepository,
                           BorrowerFreezeRepository freezeRepository,
                           UnfreezeRecordRepository unfreezeRepository,
                           CommandLogRepository commandLogRepository,
                           ObjectMapper objectMapper,
                           EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.loanRepository = loanRepository;
        this.reclaimRepository = reclaimRepository;
        this.freezeRepository = freezeRepository;
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
            throw ApiException.conflict("追缴回库待核验期间禁止发起交接: " + evidenceKey);
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
            // 追缴回库的证物经封条核验通过后回到 SEALED，方可再次借出
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
        requireBorrowerNotFrozen(request.borrowerId());
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止借出: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待接收交接期间禁止借出: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("证物已存在未归还借出: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.PENDING_INSPECTION) {
            throw ApiException.conflict("追缴回库待核验期间禁止借出: " + evidenceKey);
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
     * 逾期追缴：仅借出时的保管人可提交，借出记录须已逾期（当前 UTC 时刻不早于应还时刻）。
     * 同一事务内把借出记录转为 RECLAIMED 终态、证物转为 PENDING_INSPECTION（在库待核验），
     * 并写入不可变追缴记录（固化原到期时刻、逾期分钟数与说明）；同一借出人累计被追缴
     * 达到阈值即自动冻结。同一借出只能被追缴一次，重复提交按 reclaimKey 幂等返回首次结果。
     */
    @Transactional
    public StoredResponse reclaimLoan(String actorId, String evidenceKey,
                                      LoanReclaimRequest request, String requestHash) {
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
        // reclaimKey 业务幂等：同一追缴键重复提交（可换 commandKey）返回首次追缴结果
        Optional<ReclaimRecord> existing = reclaimRepository.findByReclaimKey(request.reclaimKey());
        if (existing.isPresent()) {
            ReclaimRecord first = existing.get();
            if (!first.loanKey().equals(request.loanKey())) {
                throw ApiException.conflict("追缴键已被其他借出记录使用: " + request.reclaimKey());
            }
            return record(request.commandKey(), OP_LOAN_RECLAIM, actorId, requestHash,
                    200, toView(first));
        }
        if (!loan.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅借出时的保管人可提交追缴: " + actorId);
        }
        if (loan.status() == LoanStatus.RETURNED) {
            throw ApiException.conflict("借出已正常归还，不得追缴: " + request.loanKey());
        }
        if (loan.status() == LoanStatus.RECLAIMED) {
            throw ApiException.conflict("借出已被追缴，不得重复追缴: " + request.loanKey());
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (nowUtc.isBefore(loan.dueAt())) {
            throw ApiException.unprocessable(
                    "借出未逾期，不得追缴；应还时刻(UTC): " + loan.dueAt());
        }
        if (!loanRepository.markReclaimed(loan.id())) {
            throw ApiException.conflict("借出已被并发归还或追缴: " + request.loanKey());
        }
        long overdueMinutes = Duration.between(loan.dueAt(), nowUtc).toMinutes();
        LocalDateTime now = LocalDateTime.now();
        reclaimRepository.insert(request.reclaimKey(), loan.loanKey(), evidenceKey, actorId,
                loan.borrowerId(), loan.dueAt(), overdueMinutes, request.note(), nowUtc, now);
        evidenceRepository.updateStatus(evidenceKey, EvidenceStatus.PENDING_INSPECTION, now);
        applyFreezeAccounting(loan.borrowerId(), actorId, now);
        ReclaimRecord created = reclaimRepository.findByReclaimKey(request.reclaimKey())
                .orElseThrow();
        return record(request.commandKey(), OP_LOAN_RECLAIM, actorId, requestHash,
                200, toView(created));
    }

    /**
     * 解冻借出人：须由另一名保管人（不同于触发冻结的追缴保管人且非借出人本人）提交说明，
     * 写入不可变解冻记录；解冻后追缴计数从零重新累计，历史追缴记录保留。
     */
    @Transactional
    public StoredResponse unfreezeBorrower(String actorId, String borrowerId,
                                           BorrowerUnfreezeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        BorrowerFreeze freeze = freezeRepository.findByBorrowerIdForUpdate(borrowerId)
                .orElseThrow(() -> ApiException.conflict("借出人未处于冻结状态: " + borrowerId));
        // 并发下本事务可能在冻结行锁上等待；持锁后复查幂等日志，重放先提交事务的首次结果。
        replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!freeze.frozen()) {
            throw ApiException.conflict("借出人未处于冻结状态: " + borrowerId);
        }
        if (borrowerId.equals(actorId)) {
            throw ApiException.conflict("借出人不能自行解冻: " + actorId);
        }
        if (freeze.frozenBy() != null && freeze.frozenBy().equals(actorId)) {
            throw ApiException.conflict("须由另一名保管人解冻，触发冻结的保管人不能解冻: " + actorId);
        }
        LocalDateTime now = LocalDateTime.now();
        unfreezeRepository.insert(borrowerId, actorId, request.note(), now);
        freezeRepository.update(borrowerId, false, 0, null, null, now);
        return record(request.commandKey(), OP_BORROWER_UNFREEZE, actorId, requestHash,
                200, new UnfreezeView(borrowerId, actorId, request.note(), now));
    }

    /**
     * 逾期清单：当前 UTC 时刻不早于应还时刻且仍未归还的借出（实时判定，不依赖后台任务）。
     */
    @Transactional(readOnly = true)
    public List<LoanView> listOverdueLoans() {
        LocalDateTime nowUtc = clock.nowUtc();
        return loanRepository.findAllActive().stream()
                .filter(loan -> !nowUtc.isBefore(loan.dueAt()))
                .map(this::toView)
                .toList();
    }

    /**
     * 按借出人查询全部追缴记录（历史记录解冻后仍保留）。
     */
    @Transactional(readOnly = true)
    public List<ReclaimView> listReclaimsByBorrower(String borrowerId) {
        return reclaimRepository.findByBorrower(borrowerId).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 查询借出人冻结状态；无冻结状态行视为正常（未冻结、计数为零）。
     */
    @Transactional(readOnly = true)
    public BorrowerFreezeView borrowerFreezeStatus(String borrowerId) {
        return freezeRepository.findByBorrowerId(borrowerId)
                .map(this::toFreezeView)
                .orElseGet(() -> new BorrowerFreezeView(borrowerId, false, 0, null, null, null));
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

    private void requireBorrowerNotFrozen(String borrowerId) {
        freezeRepository.findByBorrowerId(borrowerId)
                .filter(BorrowerFreeze::frozen)
                .ifPresent(freeze -> {
                    throw ApiException.forbidden("借出人已被冻结，禁止新借出；冻结原因: 累计被追缴 "
                            + freeze.reclaimCount() + " 次（达到 " + FREEZE_RECLAIM_THRESHOLD
                            + " 次自动冻结），须由另一名保管人解冻: " + borrowerId);
                });
    }

    /**
     * 追缴计数与自动冻结：计数加一，达到阈值即冻结并记录触发保管人；
     * 已冻结的借出人继续累计计数。冻结状态行不存在时插入，并发首次插入冲突后重查。
     */
    private void applyFreezeAccounting(String borrowerId, String actorId, LocalDateTime now) {
        Optional<BorrowerFreeze> current = freezeRepository.findByBorrowerIdForUpdate(borrowerId);
        if (current.isEmpty()) {
            try {
                freezeRepository.insert(borrowerId, now);
            } catch (DuplicateKeyException e) {
                // 并发首次追缴已先行插入，重新锁定该行后继续
            }
            current = freezeRepository.findByBorrowerIdForUpdate(borrowerId);
        }
        BorrowerFreeze freeze = current.orElseThrow();
        int count = freeze.reclaimCount() + 1;
        boolean frozen = freeze.frozen() || count >= FREEZE_RECLAIM_THRESHOLD;
        String frozenBy = freeze.frozenBy();
        LocalDateTime frozenAt = freeze.frozenAt();
        if (frozen && !freeze.frozen()) {
            frozenBy = actorId;
            frozenAt = now;
        }
        freezeRepository.update(borrowerId, frozen, count, frozenBy, frozenAt, now);
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

    private LoanView toView(LoanRecord loan) {
        // 达到应还时刻即逾期：当前 UTC 时刻不早于 dueAt 且未归还时 overdue=true，
        // 对外状态实时呈现为派生状态 OVERDUE（数据库仍存 ACTIVE，不依赖后台任务）。
        boolean overdue = loan.status() == LoanStatus.ACTIVE
                && !clock.nowUtc().isBefore(loan.dueAt());
        LoanStatus effectiveStatus = overdue ? LoanStatus.OVERDUE : loan.status();
        return new LoanView(loan.loanKey(), loan.evidenceKey(), loan.custodianId(),
                loan.borrowerId(), loan.purpose(), loan.loanAt(), loan.dueAt(), effectiveStatus,
                overdue, loan.sealPassed(), loan.returnNote(), loan.returnedAt());
    }

    private ReclaimView toView(ReclaimRecord record) {
        return new ReclaimView(record.reclaimKey(), record.loanKey(), record.evidenceKey(),
                record.custodianId(), record.borrowerId(), record.dueAt(), record.overdueMinutes(),
                record.note(), record.reclaimedAt());
    }

    private BorrowerFreezeView toFreezeView(BorrowerFreeze freeze) {
        String reason = freeze.frozen()
                ? "累计被追缴 " + freeze.reclaimCount() + " 次（达到 " + FREEZE_RECLAIM_THRESHOLD
                + " 次自动冻结），须由另一名保管人解冻"
                : null;
        return new BorrowerFreezeView(freeze.borrowerId(), freeze.frozen(), freeze.reclaimCount(),
                freeze.frozenBy(), freeze.frozenAt(), reason);
    }
}
