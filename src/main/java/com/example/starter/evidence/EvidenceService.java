package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.error.BatchValidationException;
import com.example.starter.evidence.dto.BatchIntakeRequest;
import com.example.starter.evidence.dto.BatchIntakeResponse;
import com.example.starter.evidence.dto.BatchView;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.CustodyChainView;
import com.example.starter.evidence.dto.EvidenceView;
import com.example.starter.evidence.dto.InspectionView;
import com.example.starter.evidence.dto.IntakeRequest;
import com.example.starter.evidence.dto.SealInspectionRequest;
import com.example.starter.evidence.dto.TransferInitiateRequest;
import com.example.starter.evidence.dto.TransferView;
import com.example.starter.evidence.dto.WeightReviewRequest;
import com.example.starter.evidence.dto.WeightReviewView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    static final String OP_BATCH_INTAKE = "BATCH_INTAKE";
    static final String OP_WEIGHT_REVIEW = "WEIGHT_REVIEW";
    static final String OP_TRANSFER_INITIATE = "TRANSFER_INITIATE";
    static final String OP_TRANSFER_ACCEPT = "TRANSFER_ACCEPT";
    static final String OP_TRANSFER_CANCEL = "TRANSFER_CANCEL";
    static final String OP_SEAL_INSPECTION = "SEAL_INSPECTION";

    /** 差异判定阈值：超过申报重量的 5% 记为 DISCREPANT。 */
    private static final BigDecimal DISCREPANCY_THRESHOLD = new BigDecimal("0.05");
    /** 申报重量上限（DECIMAL(10,2)）。 */
    private static final BigDecimal MAX_DECLARED = new BigDecimal("99999999.99");
    /** 实测重量上限（DECIMAL(12,4)）。 */
    private static final BigDecimal MAX_MEASURED = new BigDecimal("99999999.9999");

    private final EvidenceRepository evidenceRepository;
    private final TransferRecordRepository transferRepository;
    private final SealInspectionRepository inspectionRepository;
    private final WeightDiscrepancyRepository discrepancyRepository;
    private final WeightReviewRepository reviewRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;

    public EvidenceService(EvidenceRepository evidenceRepository,
                           TransferRecordRepository transferRepository,
                           SealInspectionRepository inspectionRepository,
                           WeightDiscrepancyRepository discrepancyRepository,
                           WeightReviewRepository reviewRepository,
                           CommandLogRepository commandLogRepository,
                           ObjectMapper objectMapper) {
        this.evidenceRepository = evidenceRepository;
        this.transferRepository = transferRepository;
        this.inspectionRepository = inspectionRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.reviewRepository = reviewRepository;
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
     * 批量入库：清单内键重复或已存在整批 400；单项格式非法整批 422；否则同一事务内原子创建
     * 全部证物为 SEALED，差异超 5% 的项标记 DISCREPANT 并进入待复核，记录差异明细。
     */
    @Transactional
    public StoredResponse batchIntake(BatchIntakeRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.intakeKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        List<BatchIntakeRequest.BatchIntakeItem> items = request.items();
        List<BigDecimal> measured = request.measuredWeights();
        if (items.size() != measured.size()) {
            throw ApiException.badRequest("实测重量列表与证物清单数量不一致");
        }
        requireNoDuplicateKeys(items);
        requireNoneExists(items);
        validateItems(items, measured);

        LocalDateTime now = LocalDateTime.now();
        List<BatchIntakeResponse.Item> results = new ArrayList<>();
        int matchedCount = 0;
        int discrepantCount = 0;
        for (int i = 0; i < items.size(); i++) {
            BatchIntakeRequest.BatchIntakeItem item = items.get(i);
            BigDecimal declared = item.declaredWeight();
            BigDecimal actual = measured.get(i);
            BigDecimal diff = actual.subtract(declared).abs();
            WeightStatus weightStatus = diff.compareTo(declared.multiply(DISCREPANCY_THRESHOLD)) > 0
                    ? WeightStatus.DISCREPANT : WeightStatus.MATCHED;
            ReviewStatus reviewStatus = weightStatus == WeightStatus.DISCREPANT
                    ? ReviewStatus.PENDING : ReviewStatus.NONE;
            evidenceRepository.insertBatch(item.evidenceKey(), request.custodianId(),
                    item.description(), declared, actual, weightStatus, reviewStatus,
                    request.intakeKey(), now);
            if (weightStatus == WeightStatus.DISCREPANT) {
                discrepantCount++;
                BigDecimal diffPercent = diff.multiply(new BigDecimal("100"))
                        .divide(declared, 4, RoundingMode.HALF_UP);
                discrepancyRepository.insert(request.intakeKey(), item.evidenceKey(),
                        declared, actual, diffPercent, now);
            } else {
                matchedCount++;
            }
            results.add(new BatchIntakeResponse.Item(item.evidenceKey(), EvidenceStatus.SEALED,
                    declared, actual, weightStatus, reviewStatus));
        }
        BatchIntakeResponse body = new BatchIntakeResponse(request.intakeKey(), request.custodianId(),
                items.size(), matchedCount, discrepantCount, results);
        return record(request.intakeKey(), OP_BATCH_INTAKE, request.custodianId(), requestHash, 201, body);
    }

    /**
     * 重量差异复核：仅当前保管人，证物须处于待复核；写入不可变复核记录并关闭待复核状态。
     * 复核不可逆，关闭后与 MATCHED 证物权限一致。
     */
    @Transactional
    public StoredResponse reviewWeight(String actorId, String evidenceKey,
                                       WeightReviewRequest request, String requestHash) {
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        Evidence evidence = lockEvidence(evidenceKey);
        requireCustodian(evidence, actorId);
        if (evidence.reviewStatus() == ReviewStatus.RESOLVED) {
            throw ApiException.conflict("复核已关闭，不可重复复核: " + evidenceKey);
        }
        if (evidence.reviewStatus() != ReviewStatus.PENDING) {
            throw ApiException.conflict("证物不存在待复核差异: " + evidenceKey);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!evidenceRepository.closeReview(evidenceKey, now)) {
            throw ApiException.conflict("复核已被处理: " + evidenceKey);
        }
        reviewRepository.insert(evidenceKey, actorId, request.note(), now);
        WeightReview review = reviewRepository.findByEvidenceKey(evidenceKey).orElseThrow();
        return record(request.commandKey(), OP_WEIGHT_REVIEW, actorId, requestHash,
                200, toView(review));
    }

    /**
     * 按批次键查询入库清单与差异复核状态。
     */
    @Transactional(readOnly = true)
    public BatchView batchView(String intakeKey) {
        List<Evidence> items = evidenceRepository.findByIntakeKey(intakeKey);
        if (items.isEmpty()) {
            throw ApiException.notFound("批次不存在: " + intakeKey);
        }
        List<BatchView.Item> views = new ArrayList<>();
        int matchedCount = 0;
        int discrepantCount = 0;
        int pendingReviewCount = 0;
        for (Evidence evidence : items) {
            String reviewNote = reviewRepository.findByEvidenceKey(evidence.evidenceKey())
                    .map(WeightReview::note).orElse(null);
            if (evidence.weightStatus() == WeightStatus.DISCREPANT) {
                discrepantCount++;
            } else {
                matchedCount++;
            }
            if (evidence.reviewStatus() == ReviewStatus.PENDING) {
                pendingReviewCount++;
            }
            views.add(new BatchView.Item(evidence.evidenceKey(), evidence.description(),
                    evidence.declaredWeight(), evidence.measuredWeight(),
                    evidence.weightStatus(), evidence.reviewStatus(), reviewNote,
                    evidence.createdAt()));
        }
        return new BatchView(intakeKey, items.get(0).custodianId(), items.size(),
                matchedCount, discrepantCount, pendingReviewCount, views);
    }

    /**
     * 清单内 evidenceKey 不得重复（空键不参与判重，按格式错误处理）。
     */
    private void requireNoDuplicateKeys(List<BatchIntakeRequest.BatchIntakeItem> items) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (BatchIntakeRequest.BatchIntakeItem item : items) {
            if (item == null || item.evidenceKey() == null || item.evidenceKey().isBlank()) {
                continue;
            }
            if (seen.merge(item.evidenceKey(), 1, Integer::sum) > 1) {
                throw ApiException.badRequest("清单内证物键重复: " + item.evidenceKey());
            }
        }
    }

    /**
     * 清单内全部 evidenceKey 不得已存在于系统。
     */
    private void requireNoneExists(List<BatchIntakeRequest.BatchIntakeItem> items) {
        for (BatchIntakeRequest.BatchIntakeItem item : items) {
            if (item == null || item.evidenceKey() == null || item.evidenceKey().isBlank()) {
                continue;
            }
            if (evidenceRepository.findByKey(item.evidenceKey()).isPresent()) {
                throw ApiException.badRequest("证物已存在: " + item.evidenceKey());
            }
        }
    }

    /**
     * 逐项格式校验：任一非法即整批 422 并返回逐项原因，不创建任何证物。
     */
    private void validateItems(List<BatchIntakeRequest.BatchIntakeItem> items,
                               List<BigDecimal> measured) {
        List<BatchValidationException.ItemError> errors = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            BatchIntakeRequest.BatchIntakeItem item = items.get(i);
            if (item == null) {
                errors.add(new BatchValidationException.ItemError(i, null, "清单项缺失"));
                continue;
            }
            String key = item.evidenceKey();
            String errorKey = key == null || key.isBlank() ? null : key;
            if (errorKey == null) {
                errors.add(new BatchValidationException.ItemError(i, null, "证物键为空"));
            }
            if (item.description() == null || item.description().isBlank()) {
                errors.add(new BatchValidationException.ItemError(i, errorKey, "描述为空"));
            }
            BigDecimal declared = item.declaredWeight();
            if (declared == null || declared.signum() <= 0) {
                errors.add(new BatchValidationException.ItemError(i, errorKey, "申报重量须大于0"));
            } else if (declared.scale() > 2 || declared.compareTo(MAX_DECLARED) > 0) {
                errors.add(new BatchValidationException.ItemError(i, errorKey,
                        "申报重量须为不超过两位小数的合法数值"));
            }
            BigDecimal actual = measured.get(i);
            if (actual == null || actual.signum() <= 0) {
                errors.add(new BatchValidationException.ItemError(i, errorKey, "实测重量须大于0"));
            } else if (actual.scale() > 4 || actual.compareTo(MAX_MEASURED) > 0) {
                errors.add(new BatchValidationException.ItemError(i, errorKey,
                        "实测重量须为不超过四位小数的合法数值"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BatchValidationException(errors);
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
        requireNotPendingReview(evidence);
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
        requireNotPendingReview(evidence);
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

    private void requireNotPendingReview(Evidence evidence) {
        if (evidence.reviewStatus() == ReviewStatus.PENDING) {
            throw ApiException.conflict("证物重量差异待复核，禁止交接与封条核验: "
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
                evidence.description(), evidence.declaredWeight(), evidence.measuredWeight(),
                evidence.weightStatus(), evidence.reviewStatus(), evidence.intakeKey(),
                evidence.createdAt(), evidence.updatedAt());
    }

    private WeightReviewView toView(WeightReview review) {
        return new WeightReviewView(review.evidenceKey(), review.reviewerId(),
                review.note(), review.createdAt());
    }

    private TransferView toView(TransferRecord record) {
        return new TransferView(record.evidenceKey(), record.fromCustodian(), record.toCustodian(),
                record.status(), record.initiatedBy(), record.createdAt(), record.decidedAt());
    }

    private InspectionView toView(SealInspection inspection) {
        return new InspectionView(inspection.evidenceKey(), inspection.inspectorId(),
                inspection.passed(), inspection.note(), inspection.createdAt());
    }
}
