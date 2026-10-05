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
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
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
     * 剂量段数量下限。
     */
    static final int MIN_DOSE_SEGMENTS = 1;

    /**
     * 剂量段数量上限。
     */
    static final int MAX_DOSE_SEGMENTS = 20;

    /**
     * 上限与照度允许的最大小数位数。
     */
    static final int DOSE_INPUT_SCALE = 3;

    /**
     * 显示用的小数位数（HALF_UP），仅展示不参与判定。
     */
    static final int DOSE_DISPLAY_SCALE = 4;

    /**
     * 一分钟的纳秒数，用于把段时长换算为精确分钟。
     */
    private static final BigDecimal NANOS_PER_MINUTE = BigDecimal.valueOf(60_000_000_000L);

    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final SealInspectionRepository inspectionRepository;
    private final LoanRecordRepository loanRepository;
    private final CommandLogRepository commandLogRepository;
    private final DoseLedgerRepository doseLedgerRepository;
    private final DoseSegmentRepository doseSegmentRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           TransferRecordRepository transferRepository,
                           SealInspectionRepository inspectionRepository,
                           LoanRecordRepository loanRepository,
                           CommandLogRepository commandLogRepository,
                           DoseLedgerRepository doseLedgerRepository,
                           DoseSegmentRepository doseSegmentRepository,
                           ObjectMapper objectMapper,
                           EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.loanRepository = loanRepository;
        this.commandLogRepository = commandLogRepository;
        this.doseLedgerRepository = doseLedgerRepository;
        this.doseSegmentRepository = doseSegmentRepository;
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
        // 剂量联合裁决：累计剂量达到上限（DOSE_BLOCKED）后证物虽回 SEALED，新增借出仍 422；交接不受影响。
        doseLedgerRepository.findByEvidenceKey(evidenceKey).ifPresent(ledger -> {
            if (ledger.doseBlocked()) {
                throw ApiException.unprocessable("累计光照剂量已达上限，禁止借出: " + evidenceKey);
            }
        });
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
     * 权限检查先于幂等重放；证物已登记光照上限且封条完好时，必须携带 1 至 20 段
     * 完整覆盖实际借出至归还的剂量段与期望账本版本，剂量精确累加不舍入；
     * 达到上限（含等于）置 DOSE_BLOCKED：归还照常成功保存实际剂量，证物回 SEALED，
     * 但新增借出 422，交接仍按原规则可行。非法或缺段 400，不归还、不记剂量。
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
        // 权限检查先于幂等重放：非借出保管人/借用人一律拒绝，不暴露首次执行结果。
        if (loan.borrowerId().equals(actorId)) {
            throw ApiException.conflict("借用人不能代为确认归还: " + actorId);
        }
        if (!loan.custodianId().equals(actorId)) {
            throw ApiException.conflict("仅借出时的保管人可确认归还: " + actorId);
        }
        // 并发下本事务可能在证物行锁上等待；持锁且通过权限后复查幂等日志，重放先提交事务的首次结果。
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
        // 剂量裁决：仅封条完好且已登记上限时核算；封条异常继续走原 SEAL_BROKEN，不记剂量。
        Optional<DoseLedger> ledgerOpt = doseLedgerRepository.findByEvidenceKey(evidenceKey);
        boolean doseApplies = request.sealIntact() && ledgerOpt.isPresent();
        if (!doseApplies && hasDosePayload(request)) {
            throw ApiException.badRequest(ledgerOpt.isEmpty()
                    ? "证物未登记光照上限，不接受剂量段或期望版本: " + evidenceKey
                    : "封条异常归还不登记剂量段: " + evidenceKey);
        }
        BigDecimal doseThisReturn = null;
        BigDecimal cumulativeAfter = null;
        List<DoseSegmentRequest> segments = List.of();
        DoseLedger ledger = null;
        if (doseApplies) {
            ledger = ledgerOpt.get();
            segments = requireSegments(request);
            if (request.expectedVersion() == null || request.expectedVersion() != ledger.version()) {
                throw ApiException.conflict("期望版本与账本当前版本不一致: " + ledger.version());
            }
            doseThisReturn = validateAndComputeDose(segments, loan.loanAt(), nowUtc);
            cumulativeAfter = ledger.cumulativeLuxMin().add(doseThisReturn);
        }
        if (!loanRepository.completeReturn(loan.id(), request.sealIntact(), request.note(), nowUtc,
                doseThisReturn, cumulativeAfter)) {
            throw ApiException.conflict("借出已被并发归还: " + request.loanKey());
        }
        inspectionRepository.insert(evidenceKey, actorId, request.sealIntact(), request.note(),
                LocalDateTime.now());
        EvidenceStatus restored = request.sealIntact()
                ? EvidenceStatus.SEALED : EvidenceStatus.SEAL_BROKEN;
        evidenceRepository.updateStatus(evidenceKey, restored, LocalDateTime.now());
        if (doseApplies) {
            // 超限归还照常成功：保存实际剂量并冻结历史；达到上限（含等于）置 DOSE_BLOCKED。
            LocalDateTime createdAt = LocalDateTime.now();
            for (int i = 0; i < segments.size(); i++) {
                DoseSegmentRequest segment = segments.get(i);
                doseSegmentRepository.insert(evidenceKey, loan.loanKey(), i + 1,
                        segment.startAt(), segment.endAt(), segment.illuminanceLux(),
                        segmentDose(segment), createdAt);
            }
            boolean blocked = cumulativeAfter.compareTo(ledger.limitLuxMin()) >= 0;
            if (!doseLedgerRepository.applyReturn(evidenceKey, cumulativeAfter, blocked,
                    ledger.version(), LocalDateTime.now())) {
                throw ApiException.conflict("账本版本已被并发变动: " + evidenceKey);
            }
        }
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

    /**
     * 光照上限首次登记：仅当前保管人，证物须为 SEALED，每证物仅一次且登记后不可变。
     * 上限为正值、最多3位小数（勒克斯分钟）；期望版本必须为 0（账本尚不存在）。
     * 权限检查先于幂等重放；登记与幂等日志在同一事务提交。
     */
    @Transactional
    public StoredResponse registerDoseLimit(String actorId, String evidenceKey,
                                            DoseLimitRegisterRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        // 权限检查先于幂等重放：非当前保管人一律拒绝，不暴露首次执行结果。
        requireCustodian(evidence, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        validateLimit(request.limitLuxMinutes());
        if (request.expectedVersion() != 0) {
            throw ApiException.conflict("期望版本与账本当前版本不一致: 首次登记须为 0");
        }
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.conflict("仅 SEALED 证物可首次登记光照上限: " + evidenceKey);
        }
        if (doseLedgerRepository.findByEvidenceKey(evidenceKey).isPresent()) {
            throw ApiException.conflict("光照上限已登记，不可变更: " + evidenceKey);
        }
        LocalDateTime now = LocalDateTime.now();
        doseLedgerRepository.insert(evidenceKey, request.limitLuxMinutes(), actorId, now);
        DoseLedger ledger = doseLedgerRepository.findByEvidenceKey(evidenceKey).orElseThrow();
        return record(request.requestId(), OP_DOSE_LIMIT_REGISTER, actorId, requestHash,
                201, toView(ledger, List.of()));
    }

    /**
     * 查询光照剂量明细：仅当前保管人可查；返回不可变上限、精确累计剂量、
     * 显示值（HALF_UP 四位，不参与判定）与全部冻结的剂量归还历史。
     */
    @Transactional(readOnly = true)
    public DoseLedgerView doseDetail(String actorId, String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        requireCustodian(evidence, actorId);
        DoseLedger ledger = doseLedgerRepository.findByEvidenceKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物未登记光照上限: " + evidenceKey));
        Map<String, List<DoseSegment>> segmentsByLoan = doseSegmentRepository
                .findByEvidenceKey(evidenceKey).stream()
                .collect(Collectors.groupingBy(DoseSegment::loanKey, LinkedHashMap::new,
                        Collectors.toList()));
        List<DoseReturnView> returns = loanRepository.findDoseReturnsByEvidenceKey(evidenceKey)
                .stream()
                .map(loan -> new DoseReturnView(loan.loanKey(), loan.returnedAt(),
                        loan.doseThisReturn(), loan.doseCumulativeAfter(),
                        displayDose(loan.doseCumulativeAfter()),
                        segmentsByLoan.getOrDefault(loan.loanKey(), List.of()).stream()
                                .map(this::toView).toList()))
                .toList();
        return toView(ledger, returns);
    }

    /**
     * 是否携带了剂量相关字段（剂量段或期望版本）。
     */
    private boolean hasDosePayload(LoanReturnRequest request) {
        return request.expectedVersion() != null
                || (request.segments() != null && !request.segments().isEmpty());
    }

    /**
     * 校验并取出剂量段（按起始时刻排序）；缺段或数量越界返回 400。
     */
    private List<DoseSegmentRequest> requireSegments(LoanReturnRequest request) {
        List<DoseSegmentRequest> segments = request.segments();
        if (segments == null || segments.size() < MIN_DOSE_SEGMENTS
                || segments.size() > MAX_DOSE_SEGMENTS) {
            throw ApiException.badRequest(
                    "剂量段数量须为 " + MIN_DOSE_SEGMENTS + " 至 " + MAX_DOSE_SEGMENTS + " 段");
        }
        return segments.stream()
                .sorted(Comparator.comparing(DoseSegmentRequest::startAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * 校验剂量段互不重叠且完整覆盖 [借出时刻, 归还时刻]，并精确累加剂量（不舍入）。
     *
     * @param segments 已按起始时刻排序的剂量段
     * @return 本次归还总剂量（勒克斯分钟）
     */
    private BigDecimal validateAndComputeDose(List<DoseSegmentRequest> segments,
                                              LocalDateTime loanAt, LocalDateTime returnedAt) {
        BigDecimal total = BigDecimal.ZERO;
        LocalDateTime cursor = loanAt;
        for (DoseSegmentRequest segment : segments) {
            if (segment.startAt() == null || segment.endAt() == null
                    || segment.illuminanceLux() == null) {
                throw ApiException.badRequest("剂量段缺少起始、结束或照度");
            }
            if (!segment.endAt().isAfter(segment.startAt())) {
                throw ApiException.badRequest("剂量段结束时刻须晚于起始时刻");
            }
            validateIlluminance(segment.illuminanceLux());
            if (!segment.startAt().equals(cursor)) {
                throw ApiException.badRequest("剂量段须互不重叠且完整覆盖实际借出至归还");
            }
            total = total.add(segmentDose(segment));
            cursor = segment.endAt();
        }
        if (!cursor.equals(returnedAt)) {
            throw ApiException.badRequest("剂量段须完整覆盖至实际归还时刻");
        }
        return total;
    }

    /**
     * 单段剂量 = 照度 × 精确分钟数（DECIMAL128 精度累加，不主动舍入）。
     */
    private BigDecimal segmentDose(DoseSegmentRequest segment) {
        BigDecimal minutes = BigDecimal
                .valueOf(Duration.between(segment.startAt(), segment.endAt()).toNanos())
                .divide(NANOS_PER_MINUTE, MathContext.DECIMAL128);
        return segment.illuminanceLux().multiply(minutes);
    }

    /**
     * 上限校验：正值、最多3位小数、整数部分不超过15位（适配 DECIMAL(18,3)）。
     */
    private void validateLimit(BigDecimal limit) {
        if (limit.signum() <= 0 || limit.scale() > DOSE_INPUT_SCALE
                || limit.precision() - limit.scale() > 15) {
            throw ApiException.badRequest("光照上限须为正值且最多3位小数（勒克斯分钟）");
        }
    }

    /**
     * 照度校验：非负、最多3位小数、整数部分不超过15位（适配 DECIMAL(18,3)）。
     */
    private void validateIlluminance(BigDecimal illuminance) {
        if (illuminance.signum() < 0 || illuminance.scale() > DOSE_INPUT_SCALE
                || illuminance.precision() - illuminance.scale() > 15) {
            throw ApiException.badRequest("照度须为非负且最多3位小数（勒克斯）");
        }
    }

    /**
     * 显示值：HALF_UP 保留4位小数，仅展示不参与判定。
     */
    private String displayDose(BigDecimal value) {
        return value.setScale(DOSE_DISPLAY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private DoseLedgerView toView(DoseLedger ledger, List<DoseReturnView> returns) {
        return new DoseLedgerView(ledger.evidenceKey(), ledger.limitLuxMin(),
                ledger.cumulativeLuxMin(), displayDose(ledger.limitLuxMin()),
                displayDose(ledger.cumulativeLuxMin()), ledger.doseBlocked(),
                ledger.registeredBy(), ledger.version(), returns);
    }

    private DoseSegmentView toView(DoseSegment segment) {
        return new DoseSegmentView(segment.segmentNo(), segment.startAt(), segment.endAt(),
                segment.illuminanceLux(), segment.doseLuxMin(), displayDose(segment.doseLuxMin()));
    }
}
