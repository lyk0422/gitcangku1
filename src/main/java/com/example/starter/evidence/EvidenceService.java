package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.error.ItemValidationException;
import com.example.starter.evidence.dto.BatchIntakeItem;
import com.example.starter.evidence.dto.BatchIntakeRequest;
import com.example.starter.evidence.dto.BatchIntakeView;
import com.example.starter.evidence.dto.BatchItemView;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.CustodyChainView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.InspectionView;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.ReviewRecordView;
import com.example.starter.evidence.dto.ReviewSubmitRequest;
import com.example.starter.evidence.dto.SealInspectionRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import com.example.starter.evidence.dto.TransferView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 证物封存交接核心服务。所有写操作在单事务内完成：
 * 先锁定证物行（SELECT ... FOR UPDATE），再校验状态机并落库，最后写入幂等日志。
 * 交接记录与核验记录只追加；证物业务字段入库后不可修改。
 */
@Service
public class EvidenceService {

    static final String OP_INTAKE = "INTAKE";
    static final String OP_BATCH_INTAKE = "BATCH_INTAKE";
    static final String OP_REVIEW_SUBMIT = "REVIEW_SUBMIT";
    static final String OP_TRANSFER_INITIATE = "TRANSFER_INITIATE";
    static final String OP_TRANSFER_ACCEPT = "TRANSFER_ACCEPT";
    static final String OP_TRANSFER_CANCEL = "TRANSFER_CANCEL";
    static final String OP_SEAL_INSPECTION = "SEAL_INSPECTION";

    /** 清单件数范围：1~50 件。 */
    static final int BATCH_MIN_ITEMS = 1;
    static final int BATCH_MAX_ITEMS = 50;
    /** 重量允许的最大小数位数：两位小数。 */
    static final int WEIGHT_SCALE = 2;
    /** 差异阈值比例：实测与申报差异严格超过申报重量 5% 记为 DISCREPANT。 */
    private static final BigDecimal DISCREPANCY_THRESHOLD = new BigDecimal("0.05");

    private final EvidenceRepository evidenceRepository;
    private final EvidenceBatchRepository batchRepository;
    private final WeightDiscrepancyRepository discrepancyRepository;
    private final ReviewRecordRepository reviewRepository;
    private final TransferRecordRepository transferRepository;
    private final SealInspectionRepository inspectionRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           EvidenceBatchRepository batchRepository,
                           WeightDiscrepancyRepository discrepancyRepository,
                           ReviewRecordRepository reviewRepository,
                           TransferRecordRepository transferRepository,
                           SealInspectionRepository inspectionRepository,
                           CommandLogRepository commandLogRepository,
                           ObjectMapper objectMapper) {
        this.evidenceRepository = evidenceRepository;
        this.batchRepository = batchRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.reviewRepository = reviewRepository;
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
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
     * 批量入库：1~50 件证物在同一事务内原子创建为 SEALED。
     * 任一项数据格式非法（描述为空、重量非正或超过两位小数）整批 422 并返回逐项原因；
     * 清单内 evidenceKey 重复或与系统既有证物冲突整批 400；以上失败均不创建任何证物、不占用 requestId。
     * 实测与申报重量差异严格超过申报 5% 的项记 DISCREPANT 并置 PENDING_REVIEW，同时记录差异明细。
     */
    @Transactional
    public StoredResponse batchIntake(String actorId, BatchIntakeRequest request, String requestHash) {
        String intakeKey = request.requestId();
        Optional<StoredResponse> replay = checkReplay(intakeKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        List<BatchIntakeItem> items = request.items() == null ? List.of() : request.items();
        List<BigDecimal> measuredWeights = request.measuredWeights();
        if (items.size() < BATCH_MIN_ITEMS || items.size() > BATCH_MAX_ITEMS) {
            throw ApiException.badRequest("清单件数必须为 1~50 件");
        }
        if (measuredWeights == null || measuredWeights.size() != items.size()) {
            throw ApiException.badRequest("实测重量列表必须与清单一一对应（" + items.size() + " 件）");
        }

        // 422：逐项数据格式校验，收集全部非法项后一次性返回。
        List<ItemValidationException.ItemError> errors = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            BatchIntakeItem item = items.get(i);
            String key = item.evidenceKey();
            if (key == null || key.isBlank()) {
                errors.add(new ItemValidationException.ItemError(i, key, "evidenceKey 不能为空"));
            } else {
                keys.add(key);
                if (!seen.add(key)) {
                    // 400：清单内重复不属于逐项数据格式问题，立即整批拒绝。
                    throw ApiException.badRequest("清单内 evidenceKey 重复: " + key);
                }
            }
            if (item.description() == null || item.description().isBlank()) {
                errors.add(new ItemValidationException.ItemError(i, key, "描述不能为空"));
            }
            validateWeight(i, key, item.declaredWeight(), "申报重量", errors);
            validateWeight(i, key, measuredWeights.get(i), "实测重量", errors);
        }
        if (!errors.isEmpty()) {
            throw new ItemValidationException(errors);
        }

        // 格式校验通过后再预占 requestId：并发同键事务在此排队等待先提交者；
        // 此前的 422/400 不写任何记录（失败不占键），事务回滚同样释放占位。
        commandLogRepository.reserve(intakeKey, actorId, OP_BATCH_INTAKE, requestHash,
                LocalDateTime.now());

        // 400：清单键全部不得存在于系统（单件入库、其他批次或并发已提交的事务）。
        List<String> existing = evidenceRepository.findExistingKeys(keys);
        if (!existing.isEmpty()) {
            throw ApiException.badRequest("证物已存在: " + String.join(", ", existing));
        }

        LocalDateTime now = LocalDateTime.now();
        List<BatchItemView> itemViews = new ArrayList<>();
        int matchedCount = 0;
        int discrepantCount = 0;
        for (int i = 0; i < items.size(); i++) {
            BatchIntakeItem item = items.get(i);
            BigDecimal declared = item.declaredWeight();
            BigDecimal measured = measuredWeights.get(i);
            BigDecimal deviation = measured.subtract(declared).abs();
            BigDecimal limit = declared.multiply(DISCREPANCY_THRESHOLD);
            WeightCheck weightCheck = deviation.compareTo(limit) > 0
                    ? WeightCheck.DISCREPANT : WeightCheck.MATCHED;
            ReviewStatus reviewStatus =
                    weightCheck == WeightCheck.DISCREPANT ? ReviewStatus.PENDING_REVIEW : null;
            // evidence_key 唯一约束兜底并发：与单件入库或其他批次冲突时整批回滚。
            evidenceRepository.insertBatchItem(intakeKey, actorId, item, measured,
                    weightCheck, reviewStatus, now);
            if (weightCheck == WeightCheck.DISCREPANT) {
                discrepantCount++;
                BigDecimal ratio = deviation.divide(declared, 6, RoundingMode.HALF_UP);
                discrepancyRepository.insert(intakeKey, item.evidenceKey(), declared, measured,
                        deviation.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP), ratio, now);
            } else {
                matchedCount++;
            }
            itemViews.add(new BatchItemView(item.evidenceKey(), item.description(),
                    declared, measured, weightCheck, reviewStatus));
        }
        batchRepository.insert(intakeKey, actorId, items.size(), matchedCount, discrepantCount, now);

        BatchIntakeView view = new BatchIntakeView(intakeKey, actorId, items.size(),
                matchedCount, discrepantCount, discrepantCount, itemViews, now);
        return completeReserved(intakeKey, 201, view);
    }

    private StoredResponse completeReserved(String commandKey, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.complete(commandKey, status, json, LocalDateTime.now());
        return new StoredResponse(status, json);
    }

    private void validateWeight(int index, String evidenceKey, BigDecimal weight, String label,
                                List<ItemValidationException.ItemError> errors) {
        if (weight == null) {
            errors.add(new ItemValidationException.ItemError(index, evidenceKey, label + "缺失"));
        } else if (weight.signum() <= 0) {
            errors.add(new ItemValidationException.ItemError(index, evidenceKey, label + "必须大于 0"));
        } else if (weight.stripTrailingZeros().scale() > WEIGHT_SCALE) {
            errors.add(new ItemValidationException.ItemError(index, evidenceKey,
                    label + "最多允许两位小数"));
        }
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
        if (request.toCustodian().equals(actorId)) {
            throw ApiException.badRequest("接收人不能与发起保管人相同");
        }
        requireSealIntact(evidence);
        requireReviewCleared(evidence);
        requireCustodian(evidence, actorId);
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("证物已存在待接收交接: " + evidenceKey);
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
        requireCustodian(evidence, actorId);
        requireReviewCleared(evidence);
        if (evidence.status() == EvidenceStatus.TRANSFER_PENDING) {
            throw ApiException.conflict("待接收期间禁止封条核验: " + evidenceKey);
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
     * 提交差异复核：仅证物当前保管人（批次保管人）可提交，说明必填；
     * 复核记录只追加不可变，提交后复核状态不可逆地关闭，证物权限与 MATCHED 一致。
     */
    @Transactional
    public StoredResponse submitReview(String actorId, String evidenceKey,
                                       ReviewSubmitRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        if (evidence.reviewStatus() == null) {
            throw ApiException.conflict("证物无需差异复核: " + evidenceKey);
        }
        if (evidence.reviewStatus() == ReviewStatus.REVIEWED) {
            throw ApiException.conflict("复核已关闭，不可重复提交: " + evidenceKey);
        }
        requireCustodian(evidence, actorId);
        LocalDateTime now = LocalDateTime.now();
        reviewRepository.insert(evidence.intakeKey(), evidenceKey, actorId, request.note(), now);
        if (!evidenceRepository.closeReview(evidenceKey, now)) {
            throw ApiException.conflict("复核已被其他事务关闭: " + evidenceKey);
        }
        ReviewRecordView view = new ReviewRecordView(evidence.intakeKey(), evidenceKey,
                actorId, request.note(), now);
        return record(request.commandKey(), OP_REVIEW_SUBMIT, actorId, requestHash, 200, view);
    }

    /**
     * 按批次查询入库清单与差异复核状态。
     */
    @Transactional(readOnly = true)
    public BatchIntakeView batchView(String intakeKey) {
        EvidenceBatch batch = batchRepository.findByIntakeKey(intakeKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + intakeKey));
        List<BatchItemView> items = evidenceRepository.findByIntakeKey(intakeKey).stream()
                .map(this::toBatchItemView)
                .toList();
        return new BatchIntakeView(batch.intakeKey(), batch.custodianId(), batch.totalCount(),
                batch.matchedCount(), batch.discrepantCount(),
                evidenceRepository.countPendingReview(intakeKey), items, batch.createdAt());
    }

    /**
     * 查询批次全部复核记录（只追加，按提交顺序）。
     */
    @Transactional(readOnly = true)
    public List<ReviewRecordView> batchReviews(String intakeKey) {
        batchRepository.findByIntakeKey(intakeKey)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + intakeKey));
        return reviewRepository.findByIntakeKey(intakeKey).stream()
                .map(record -> new ReviewRecordView(record.intakeKey(), record.evidenceKey(),
                        record.reviewerId(), record.note(), record.createdAt()))
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
     * 查询完整保管链：证物当前状态 + 全部交接记录 + 全部核验记录。
     */
    @Transactional(readOnly = true)
    public CustodyChainView custodyChain(String evidenceKey) {
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        List<TransferView> transfers = transferRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
        List<InspectionView> inspections = inspectionRepository.findByEvidenceKey(evidenceKey)
                .stream().map(this::toView).toList();
        return new CustodyChainView(toView(evidence), transfers, inspections);
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

    private void requireReviewCleared(Evidence evidence) {
        if (evidence.reviewStatus() == ReviewStatus.PENDING_REVIEW) {
            throw ApiException.unprocessable("证物存在待复核重量差异，禁止交接与封条核验: "
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

    private BatchItemView toBatchItemView(Evidence evidence) {
        return new BatchItemView(evidence.evidenceKey(), evidence.description(),
                evidence.declaredWeight(), evidence.measuredWeight(),
                evidence.weightCheck(), evidence.reviewStatus());
    }
}
