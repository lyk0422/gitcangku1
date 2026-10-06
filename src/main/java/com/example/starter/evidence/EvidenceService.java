package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.CustodyChainView;
import com.example.starter.evidence.dto.DoseLedgerView;
import com.example.starter.evidence.dto.DoseLimitRegisterRequest;
import com.example.starter.evidence.dto.DoseReturnView;
import com.example.starter.evidence.dto.DoseSegmentRequest;
import com.example.starter.evidence.dto.DoseSegmentView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.InspectionView;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.LoanCreateRequest;
import com.example.starter.evidence.dto.LoanReturnRequest;
import com.example.starter.evidence.dto.LoanView;
import com.example.starter.evidence.dto.SealInspectionRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import com.example.starter.evidence.dto.TransferView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
    static final String OP_DOSE_LIMIT_REGISTER = "DOSE_LIMIT_REGISTER";

    /**
     * 借出最长期限：72 小时。
     */
    static final long MAX_LOAN_HOURS = 72;

    /**
     * 单次归还允许上报的光照区间段数上限。
     */
    static final int MAX_DOSE_SEGMENTS = 20;

    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final SealInspectionRepository inspectionRepository;
    private final LoanRecordRepository loanRepository;
    private final CommandLogRepository commandLogRepository;
    private final DoseLedgerRepository doseLedgerRepository;
    private final DoseReturnRepository doseReturnRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           TransferRecordRepository transferRepository,
                           SealInspectionRepository inspectionRepository,
                           LoanRecordRepository loanRepository,
                           CommandLogRepository commandLogRepository,
                           DoseLedgerRepository doseLedgerRepository,
                           DoseReturnRepository doseReturnRepository,
                           ObjectMapper objectMapper,
                           EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.loanRepository = loanRepository;
        this.commandLogRepository = commandLogRepository;
        this.doseLedgerRepository = doseLedgerRepository;
        this.doseReturnRepository = doseReturnRepository;
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
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止借出: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待接收交接期间禁止借出: " + evidenceKey);
        }
        if (evidence.status() == EvidenceStatus.BORROWED) {
            throw ApiException.conflict("证物已存在未归还借出: " + evidenceKey);
        }
        // 剂量联合裁决：已登记上限且累计达到上限（DOSE_BLOCKED）时禁止新的借出；
        // 未登记上限的证物不受此规则影响。
        Optional<DoseLedger> ledger = doseLedgerRepository.findByEvidenceKey(evidenceKey);
        if (ledger.isPresent()
                && DoseMath.reachesLimit(ledger.get().cumMilliLuxNanos(), ledger.get().limitLuxMinutes())) {
            throw ApiException.unprocessable("累计剂量已达上限（DOSE_BLOCKED），禁止借出: " + evidenceKey);
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
     * 已登记剂量上限的证物：必须携带 1 至 20 段光照区间（非法或缺段 400，不归还也不记剂量），
     * 剂量按区间精确累计并冻结；超限归还照常成功保存实际剂量，后续借出按 DOSE_BLOCKED 拒绝。
     * 权限检查先于幂等重放；重放返回首次结果，不重复累计剂量。
     */
    @Transactional
    public StoredResponse returnLoan(String actorId, String evidenceKey,
                                     LoanReturnRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
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
        // 权限检查先于重放：持锁并确认操作人资格后才命中幂等日志。
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
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
        Optional<DoseLedger> ledger = doseLedgerRepository.findByEvidenceKey(evidenceKey);
        LocalDateTime returnedAt = nowUtc;
        List<BigDecimal> segmentDoses = List.of();
        BigDecimal returnDose = null;
        BigDecimal newCum = null;
        if (ledger.isPresent()) {
            segmentDoses = validateDoseSegments(loan, request.segments(), nowUtc);
            returnedAt = request.segments().get(request.segments().size() - 1).endAt();
            returnDose = segmentDoses.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            newCum = ledger.get().cumMilliLuxNanos().add(returnDose);
        } else if (request.segments() != null && !request.segments().isEmpty()) {
            throw ApiException.badRequest("该证物未登记剂量上限，不接受剂量区间: " + evidenceKey);
        }
        if (!loanRepository.completeReturn(loan.id(), request.sealIntact(), request.note(), returnedAt)) {
            throw ApiException.conflict("借出已被并发归还: " + request.loanKey());
        }
        LocalDateTime now = LocalDateTime.now();
        if (ledger.isPresent()) {
            // 剂量归还、账本累计与借出归还在同一事务落库；即使超限也必须成功保存实际剂量。
            doseReturnRepository.insertReturn(evidenceKey, request.loanKey(), returnDose, newCum,
                    returnedAt, now);
            for (int i = 0; i < request.segments().size(); i++) {
                DoseSegmentRequest segment = request.segments().get(i);
                doseReturnRepository.insertSegment(evidenceKey, request.loanKey(), i + 1,
                        segment.startAt(), segment.endAt(), segment.lux(), segmentDoses.get(i), now);
            }
            doseLedgerRepository.addDose(evidenceKey, newCum, now);
        }
        inspectionRepository.insert(evidenceKey, actorId, request.sealIntact(), request.note(), now);
        EvidenceStatus restored = request.sealIntact()
                ? EvidenceStatus.SEALED : EvidenceStatus.SEAL_BROKEN;
        evidenceRepository.updateStatus(evidenceKey, restored, now);
        LoanRecord returned = loanRepository.findByLoanKey(request.loanKey()).orElseThrow();
        return record(request.commandKey(), OP_LOAN_RETURN, actorId, requestHash,
                200, toView(returned));
    }

    /**
     * 校验归还上报的光照区间并逐段计算剂量（内部精确单位，无舍入）。
     * 要求 1 至 20 段，自首段等于实际借出时刻起首尾相接、互不重叠，
     * 末段结束时刻即实际归还时刻，不得晚于服务端当前时刻。
     */
    private List<BigDecimal> validateDoseSegments(LoanRecord loan, List<DoseSegmentRequest> segments,
                                                  LocalDateTime nowUtc) {
        if (segments == null || segments.isEmpty()) {
            throw ApiException.badRequest("已登记剂量上限，归还须包含 1 至 " + MAX_DOSE_SEGMENTS
                    + " 段光照区间: " + loan.loanKey());
        }
        if (segments.size() > MAX_DOSE_SEGMENTS) {
            throw ApiException.badRequest("光照区间段数不得超过 " + MAX_DOSE_SEGMENTS);
        }
        LocalDateTime expectedStart = loan.loanAt();
        for (DoseSegmentRequest segment : segments) {
            if (!segment.startAt().isBefore(segment.endAt())) {
                throw ApiException.badRequest("区间开始时刻须早于结束时刻");
            }
            if (!segment.startAt().equals(expectedStart)) {
                throw ApiException.badRequest("区间须自实际借出时刻起首尾相接且互不重叠");
            }
            expectedStart = segment.endAt();
        }
        LocalDateTime returnedAt = segments.get(segments.size() - 1).endAt();
        if (returnedAt.isAfter(nowUtc)) {
            throw ApiException.badRequest("归还时刻不能晚于服务端当前时刻");
        }
        return segments.stream()
                .map(segment -> DoseMath.segmentDose(segment.lux(), segment.startAt(), segment.endAt()))
                .toList();
    }

    /**
     * 登记剂量上限：仅当前保管人、证物须为 SEALED、首次登记（不可变）。
     * 权限检查先于幂等重放；提供期望版本时必须为 0（登记前账本不存在）。
     */
    @Transactional
    public StoredResponse registerDoseLimit(String actorId, String evidenceKey,
                                            DoseLimitRegisterRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        // 权限检查先于重放。
        requireCustodian(evidence, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条已异常，禁止登记剂量上限: " + evidenceKey);
        }
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("仅 SEALED 状态可登记剂量上限: " + evidenceKey);
        }
        if (doseLedgerRepository.findByEvidenceKey(evidenceKey).isPresent()) {
            throw ApiException.conflict("剂量上限已登记，不可变更: " + evidenceKey);
        }
        if (request.expectedVersion() != null && request.expectedVersion() != 0) {
            throw ApiException.conflict("期望版本不匹配，登记前账本不存在（版本 0）: " + evidenceKey);
        }
        LocalDateTime now = LocalDateTime.now();
        doseLedgerRepository.insert(evidenceKey, request.limitLuxMinutes(), now);
        DoseLedger ledger = doseLedgerRepository.findByEvidenceKey(evidenceKey).orElseThrow();
        return record(request.requestId(), OP_DOSE_LIMIT_REGISTER, actorId, requestHash,
                201, toView(ledger, List.of(), List.of()));
    }

    /**
     * 查询剂量账本明细：仅当前保管人可查（权限先于一切），
     * 历史冻结照度、区间、当次与累计剂量；保管权交接不清账，新保管人继承完整账本。
     */
    @Transactional(readOnly = true)
    public DoseLedgerView doseLedger(String actorId, String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        requireCustodian(evidence, actorId);
        DoseLedger ledger = doseLedgerRepository.findByEvidenceKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物未登记剂量上限: " + evidenceKey));
        List<DoseReturnRecord> returns = doseReturnRepository.findReturnsByEvidenceKey(evidenceKey);
        List<DoseSegmentRecord> segments = doseReturnRepository.findSegmentsByEvidenceKey(evidenceKey);
        return toView(ledger, returns, segments);
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
        // 达到应还时刻即逾期：当前 UTC 时刻不早于 dueAt 且未归还时 overdue=true。
        boolean overdue = loan.status() == LoanStatus.ACTIVE
                && !clock.nowUtc().isBefore(loan.dueAt());
        return new LoanView(loan.loanKey(), loan.evidenceKey(), loan.custodianId(),
                loan.borrowerId(), loan.purpose(), loan.loanAt(), loan.dueAt(), loan.status(),
                overdue, loan.sealPassed(), loan.returnNote(), loan.returnedAt());
    }

    private DoseLedgerView toView(DoseLedger ledger, List<DoseReturnRecord> returns,
                                  List<DoseSegmentRecord> segments) {
        Map<String, List<DoseSegmentRecord>> segmentsByLoan = segments.stream()
                .collect(Collectors.groupingBy(DoseSegmentRecord::loanKey,
                        LinkedHashMap::new, Collectors.toList()));
        List<DoseReturnView> returnViews = returns.stream()
                .map(record -> new DoseReturnView(record.loanKey(), record.returnedAt(),
                        DoseMath.toDisplayLuxMinutes(record.returnMilliLuxNanos()),
                        DoseMath.toDisplayLuxMinutes(record.cumMilliLuxNanos()),
                        segmentsByLoan.getOrDefault(record.loanKey(), List.of()).stream()
                                .map(segment -> new DoseSegmentView(segment.seq(), segment.startAt(),
                                        segment.endAt(), segment.lux(),
                                        DoseMath.toDisplayLuxMinutes(segment.milliLuxNanos())))
                                .toList()))
                .toList();
        DoseStatus status = DoseMath.reachesLimit(ledger.cumMilliLuxNanos(), ledger.limitLuxMinutes())
                ? DoseStatus.DOSE_BLOCKED : DoseStatus.OK;
        return new DoseLedgerView(ledger.evidenceKey(), ledger.limitLuxMinutes(),
                DoseMath.toDisplayLuxMinutes(ledger.cumMilliLuxNanos()), status,
                ledger.version(), returnViews);
    }
}
