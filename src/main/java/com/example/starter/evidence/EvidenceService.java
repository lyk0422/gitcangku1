package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.ContactBatchRequest;
import com.example.starter.evidence.dto.ContactBatchView;
import com.example.starter.evidence.dto.ContaminationRequest;
import com.example.starter.evidence.dto.ContaminationView;
import com.example.starter.evidence.dto.CustodyChainView;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    static final String OP_CONTACT_BATCH = "CONTACT_BATCH";
    static final String OP_CONTAMINATION_REGISTER = "CONTAMINATION_REGISTER";

    /**
     * 借出最长期限：72 小时。
     */
    static final long MAX_LOAN_HOURS = 72;

    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final SealInspectionRepository inspectionRepository;
    private final LoanRecordRepository loanRepository;
    private final CommandLogRepository commandLogRepository;
    private final ContactBatchRepository contactBatchRepository;
    private final ContaminationRepository contaminationRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           TransferRecordRepository transferRepository,
                           SealInspectionRepository inspectionRepository,
                           LoanRecordRepository loanRepository,
                           CommandLogRepository commandLogRepository,
                           ContactBatchRepository contactBatchRepository,
                           ContaminationRepository contaminationRepository,
                           ObjectMapper objectMapper,
                           EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.loanRepository = loanRepository;
        this.commandLogRepository = commandLogRepository;
        this.contactBatchRepository = contactBatchRepository;
        this.contaminationRepository = contaminationRepository;
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
        requireNotContaminated(evidence);
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
        requireNotContaminated(evidence);
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
        requireNotContaminated(evidence);
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
     * 登记接触批次：当前保管人登记同属自己的 2~5 件 SEALED 且未污染证物共用处理台。
     * 全部成员证物行按证物键排序锁定（SELECT ... FOR UPDATE），持锁后重新核对状态，
     * 与污染传播在同一行锁串行边界内，保证扩散后不会插入漏管批次。
     * 批次只追加，事件序号全局单调递增，时刻取服务器 UTC。
     */
    @Transactional
    public StoredResponse registerContactBatch(String actorId, ContactBatchRequest request,
                                               String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<String> memberKeys = request.evidenceKeys().stream().distinct().sorted().toList();
        if (memberKeys.size() != request.evidenceKeys().size()) {
            throw ApiException.badRequest("接触批次成员不得重复");
        }
        // 与污染登记同一串行边界：先取接触域单行锁，再锁成员证物行并核对状态。
        contactBatchRepository.lockDomain();
        for (String evidenceKey : memberKeys) {
            Evidence evidence = lockEvidence(evidenceKey);
            requireCustodian(evidence, actorId);
            if (evidence.contaminated()) {
                throw ApiException.conflict("证物已污染隔离，不得参加新接触: " + evidenceKey);
            }
            if (evidence.status() != EvidenceStatus.SEALED) {
                throw ApiException.conflict("仅正常 SEALED 证物可参加接触批次（当前 "
                        + evidence.status() + "）: " + evidenceKey);
            }
        }
        LocalDateTime nowUtc = clock.nowUtc();
        long eventSeq = contactBatchRepository.insertBatch(actorId, nowUtc);
        for (String evidenceKey : memberKeys) {
            contactBatchRepository.insertMember(eventSeq, evidenceKey);
        }
        return record(request.commandKey(), OP_CONTACT_BATCH, actorId, requestHash,
                201, new ContactBatchView(eventSeq, actorId, memberKeys, nowUtc));
    }

    /**
     * 污染登记：指定一件证物及一个已有事件序号作为污染起点。
     * 仅起点证物当前保管人可登记（越权 403，先于幂等重放判定）。
     * 按事件序号递增扫描起点及之后接触批次：含已污染成员则全部成员加入污染集合，
     * 之前批次不追溯；整次原子置位所有受影响证物的污染标记（与封条异常分别保存），
     * 传播路径随污染事件冻结，污染不可解除。
     */
    @Transactional
    public StoredResponse registerContamination(String actorId, ContaminationRequest request,
                                                String requestHash) {
        // 权限先于重放：仅起点证物当前保管人可登记，越权 403 不进入幂等判定。
        Evidence origin = evidenceRepository.findByKey(request.evidenceKey())
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + request.evidenceKey()));
        if (!origin.custodianId().equals(actorId)) {
            throw ApiException.forbidden("仅起点证物当前保管人可登记污染: " + actorId);
        }
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!contactBatchRepository.existsBatch(request.originEventSeq())) {
            throw ApiException.notFound("接触事件序号不存在: " + request.originEventSeq());
        }
        // 与接触批次登记同一串行边界：持锁期间扫描到的批次集合是提交确定的。
        contactBatchRepository.lockDomain();
        // 按事件序号递增传播：起点证物先入集合，含已污染成员的批次全部成员加入。
        Map<String, Long> contaminated = new LinkedHashMap<>();
        contaminated.put(request.evidenceKey(), request.originEventSeq());
        for (ContactBatchRepository.ContactBatch batch
                : contactBatchRepository.findBatchesFrom(request.originEventSeq())) {
            List<String> members = contactBatchRepository.findMemberKeys(batch.eventSeq());
            boolean hit = members.stream().anyMatch(contaminated::containsKey);
            if (hit) {
                for (String member : members) {
                    contaminated.putIfAbsent(member, batch.eventSeq());
                }
            }
        }
        // 同一串行边界：按证物键排序锁定全部受影响证物行后原子置位污染标记。
        LocalDateTime nowUtc = clock.nowUtc();
        List<String> affectedKeys = contaminated.keySet().stream().sorted().toList();
        for (String evidenceKey : affectedKeys) {
            lockEvidence(evidenceKey);
            evidenceRepository.markContaminated(evidenceKey, nowUtc);
        }
        long contaminationId = contaminationRepository.insertEvent(
                request.evidenceKey(), request.originEventSeq(), actorId, nowUtc);
        for (String evidenceKey : affectedKeys) {
            contaminationRepository.insertMember(contaminationId, evidenceKey,
                    contaminated.get(evidenceKey));
        }
        return record(request.commandKey(), OP_CONTAMINATION_REGISTER, actorId, requestHash,
                201, new ContaminationView(contaminationId, request.evidenceKey(),
                        request.originEventSeq(), actorId, affectedKeys, nowUtc));
    }

    /**
     * 查询污染事件详情：起点、起点事件序号与冻结的传播路径。
     */
    @Transactional(readOnly = true)
    public ContaminationView contaminationDetail(long contaminationId) {
        ContaminationRepository.ContaminationEvent event = contaminationRepository
                .findEventById(contaminationId)
                .orElseThrow(() -> ApiException.notFound("污染事件不存在: " + contaminationId));
        List<String> keys = contaminationRepository.findMembers(contaminationId).stream()
                .map(ContaminationRepository.ContaminationMember::evidenceKey)
                .toList();
        return new ContaminationView(event.id(), event.originEvidenceKey(), event.originEventSeq(),
                event.registeredBy(), keys, event.createdAt());
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

    private void requireNotContaminated(Evidence evidence) {
        if (evidence.contaminated()) {
            throw ApiException.unprocessable("证物已污染隔离，禁止借出、交接及新接触: "
                    + evidence.evidenceKey());
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
                evidence.contaminated(), evidence.createdAt(), evidence.updatedAt());
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
}
