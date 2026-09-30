package com.example.starter.germination.service;

import com.example.starter.germination.domain.BatchRelease;
import com.example.starter.germination.domain.CertificateStatus;
import com.example.starter.germination.domain.GerminationCertificate;
import com.example.starter.germination.domain.GerminationTest;
import com.example.starter.germination.domain.SeedBatch;
import com.example.starter.germination.domain.TestStatus;
import com.example.starter.germination.repository.GerminationRepository;
import com.example.starter.germination.repository.IdempotentRecord;
import com.example.starter.germination.repository.IdempotentRequestRepository;
import com.example.starter.germination.web.BatchChainView;
import com.example.starter.germination.web.CertificateView;
import com.example.starter.germination.web.IssueCertificateRequest;
import com.example.starter.germination.web.ReleaseBatchRequest;
import com.example.starter.germination.web.ReleaseView;
import com.example.starter.germination.web.RegisterTestRequest;
import com.example.starter.germination.web.TestView;
import com.example.starter.germination.web.VoidTestRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 发芽率证书业务服务：检测登记/作废、证书签发、批次放行与检测链查询。
 *
 * <p>所有写操作先对批次行加 {@code FOR UPDATE} 行锁，使同一批次的作废与放行等操作
 * 严格按事务提交顺序串行化；不同批次互不阻塞。每个写操作整体运行在单个事务内，
 * 幂等记录与业务数据同生共死：事务异常回滚后不残留 PROCESSING 记录，允许原 requestId 重试。</p>
 */
@Service
public class GerminationService {

    private final GerminationRepository repository;
    private final IdempotentRequestRepository idempotentRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public GerminationService(GerminationRepository repository,
                              IdempotentRequestRepository idempotentRepository,
                              ObjectMapper objectMapper,
                              Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.idempotentRepository = idempotentRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 检测登记 ============================

    /** 登记一次检测（复检为追加新记录，不修改任何历史记录）。 */
    public TestView registerTest(RegisterTestRequest request) {
        return runIdempotent(Operation.REGISTER_TEST, request.requestId(),
                paramsOf(operationParams(Operation.REGISTER_TEST, request)),
                TestView.class, () -> {
                    SeedBatch batch = lockExistingBatch(request.batchNo());
                    int sample = request.sampleCount();
                    int germinated = request.germinatedCount();
                    if (!GerminationRules.isSampleCountValid(sample)) {
                        throw new BusinessRejectedException(RejectReason.SAMPLE_OUT_OF_RANGE,
                                "样本数须在100至400粒之间，实际" + sample + "粒");
                    }
                    if (!GerminationRules.isGerminatedCountValid(sample, germinated)) {
                        throw new BusinessRejectedException(RejectReason.GERMINATED_EXCEEDS_SAMPLE,
                                "发芽粒数" + germinated + "不得大于样本数" + sample);
                    }
                    Optional<GerminationTest> existing = repository.findTest(request.testNo());
                    if (existing.isPresent()) {
                        GerminationTest prior = existing.get();
                        if (prior.batchNo().equals(request.batchNo())) {
                            throw new BusinessRejectedException(RejectReason.TEST_NO_DUPLICATED,
                                    "检测编号" + request.testNo() + "已登记，检测记录不可重复使用");
                        }
                        throw new BusinessRejectedException(RejectReason.TEST_BELONGS_TO_OTHER_BATCH,
                                "检测编号" + request.testNo() + "已归属批次" + prior.batchNo()
                                        + "，同一检测编号只能归属一个批次");
                    }
                    GerminationTest test = new GerminationTest(request.testNo(), batch.batchNo(),
                            sample, germinated, TestStatus.VALID, clock.instant(), null);
                    try {
                        repository.insertTest(test);
                    } catch (DuplicateKeyException e) {
                        // 数据库唯一约束兜底：并发登记同一检测编号，绝不允许归属两个批次。
                        throw new BusinessRejectedException(RejectReason.TEST_NO_DUPLICATED,
                                "检测编号" + request.testNo() + "已存在，检测记录不可重复使用");
                    }
                    return toTestView(test);
                });
    }

    // ============================ 检测作废 ============================

    /** 作废取样检测记录；未放行证书若固化了该检测则同步失效，已放行批次不回滚。 */
    public TestView voidTest(VoidTestRequest request) {
        return runIdempotent(Operation.VOID_TEST, request.requestId(),
                paramsOf(operationParams(Operation.VOID_TEST, request)),
                TestView.class, () -> {
                    lockExistingBatch(request.batchNo());
                    GerminationTest test = repository.findTestForUpdate(request.testNo())
                            .orElseThrow(() -> new NotFoundException(RejectReason.TEST_NOT_FOUND,
                                    "检测编号" + request.testNo() + "不存在"));
                    if (!test.batchNo().equals(request.batchNo())) {
                        throw new BusinessRejectedException(RejectReason.TEST_BELONGS_TO_OTHER_BATCH,
                                "检测编号" + request.testNo() + "归属批次" + test.batchNo()
                                        + "，不能按批次" + request.batchNo() + "作废");
                    }
                    if (test.status() == TestStatus.VOID) {
                        throw new BusinessRejectedException(RejectReason.TEST_ALREADY_VOID,
                                "检测编号" + request.testNo() + "已作废，作废不可重复执行");
                    }
                    Instant now = clock.instant();
                    repository.markTestVoid(test.testNo(), now);
                    invalidateUnreleasedCertificateIfDependsOn(test.batchNo(), test.testNo());
                    GerminationTest voided = new GerminationTest(test.testNo(), test.batchNo(),
                            test.sampleCount(), test.germinatedCount(), TestStatus.VOID,
                            test.recordedAt(), now);
                    return toTestView(voided);
                });
    }

    /**
     * 未放行批次的当前证书若固化了被作废检测，则转为失效；
     * 已放行批次保留当时证书快照（VALID）与放行事实，不做任何改动。
     */
    private void invalidateUnreleasedCertificateIfDependsOn(String batchNo, String voidedTestNo) {
        Optional<GerminationCertificate> latest = repository.findCertificateByBatchForUpdate(batchNo);
        if (latest.isEmpty() || latest.get().status() == CertificateStatus.INVALID) {
            return;
        }
        GerminationCertificate certificate = latest.get();
        boolean released = repository.findRelease(batchNo).isPresent();
        if (released) {
            return;
        }
        boolean dependsOnVoided = certificate.firstTestNo().equals(voidedTestNo)
                || certificate.secondTestNo().equals(voidedTestNo);
        if (dependsOnVoided) {
            repository.markCertificateInvalid(certificate.certNo());
        }
    }

    // ============================ 证书签发 ============================

    /** 依据最近两次有效检测（均 ≥ 85%）签发证书，证书固化检测编号、比例与签发时刻。 */
    public CertificateView issueCertificate(IssueCertificateRequest request) {
        return runIdempotent(Operation.ISSUE_CERTIFICATE, request.requestId(),
                paramsOf(operationParams(Operation.ISSUE_CERTIFICATE, request)),
                CertificateView.class, () -> {
                    lockExistingBatch(request.batchNo());
                    List<GerminationTest> validTests = repository.findTestsByBatch(request.batchNo()).stream()
                            .filter(t -> t.status() == TestStatus.VALID)
                            .toList();
                    if (validTests.size() < 2) {
                        throw new BusinessRejectedException(RejectReason.NOT_ENOUGH_VALID_TESTS,
                                "有效检测不足两次，当前" + validTests.size() + "次，不能签发证书");
                    }
                    GerminationTest first = validTests.get(validTests.size() - 2);
                    GerminationTest second = validTests.get(validTests.size() - 1);
                    if (!GerminationRules.isPassing(first.sampleCount(), first.germinatedCount())
                            || !GerminationRules.isPassing(second.sampleCount(), second.germinatedCount())) {
                        throw new BusinessRejectedException(RejectReason.RATIO_BELOW_THRESHOLD,
                                "最近两次有效检测发芽率均须达到85%，实际为"
                                        + first.ratioPercent() + "%、" + second.ratioPercent() + "%");
                    }
                    Optional<GerminationCertificate> existing =
                            repository.findCertificateByBatchForUpdate(request.batchNo());
                    if (existing.isPresent() && existing.get().status() == CertificateStatus.VALID) {
                        throw new ConflictException(RejectReason.CERTIFICATE_ALREADY_EXISTS,
                                "批次" + request.batchNo() + "已存在有效证书"
                                        + existing.get().certNo() + "，证书不可覆盖");
                    }
                    GerminationCertificate certificate = new GerminationCertificate(
                            "CERT-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                            request.batchNo(),
                            first.testNo(), first.ratioPercent(),
                            second.testNo(), second.ratioPercent(),
                            CertificateStatus.VALID, clock.instant());
                    repository.insertCertificate(certificate);
                    return toCertificateView(certificate);
                });
    }

    // ============================ 批次放行 ============================

    /** 凭当前有效证书放行批次；无证书、证书失效、重复放场均返回 409。 */
    public ReleaseView releaseBatch(ReleaseBatchRequest request) {
        return runIdempotent(Operation.RELEASE_BATCH, request.requestId(),
                paramsOf(operationParams(Operation.RELEASE_BATCH, request)),
                ReleaseView.class, () -> {
                    lockExistingBatch(request.batchNo());
                    if (repository.findReleaseForUpdate(request.batchNo()).isPresent()) {
                        throw new ConflictException(RejectReason.BATCH_ALREADY_RELEASED,
                                "批次" + request.batchNo() + "已放行，不能重复放行");
                    }
                    GerminationCertificate certificate = repository
                            .findCertificateByBatchForUpdate(request.batchNo())
                            .orElseThrow(() -> new ConflictException(RejectReason.CERTIFICATE_NOT_FOUND,
                                    "批次" + request.batchNo() + "尚无证书，不能放行"));
                    if (certificate.status() == CertificateStatus.INVALID) {
                        throw new ConflictException(RejectReason.CERTIFICATE_INVALID,
                                "证书" + certificate.certNo() + "已失效，不能凭失效证书放行");
                    }
                    Instant now = clock.instant();
                    repository.insertRelease(new BatchRelease(request.batchNo(),
                            certificate.certNo(), now));
                    return new ReleaseView(true, certificate.certNo(), now);
                });
    }

    // ============================ 检测链查询 ============================

    /** 按批次查询检测链、当前证书（含其依赖的检测编号）与放行状态。 */
    public BatchChainView getBatchChain(String batchNo) {
        SeedBatch batch = repository.findBatch(batchNo)
                .orElseThrow(() -> new NotFoundException(RejectReason.BATCH_NOT_FOUND,
                        "批次" + batchNo + "不存在"));
        List<TestView> tests = repository.findTestsByBatch(batchNo).stream()
                .map(this::toTestView)
                .toList();
        CertificateView certificate = repository.findCertificateByBatch(batchNo)
                .map(this::toCertificateView)
                .orElse(null);
        ReleaseView release = repository.findRelease(batchNo)
                .map(r -> new ReleaseView(true, r.certNo(), r.releasedAt()))
                .orElseGet(() -> new ReleaseView(false, null, null));
        return new BatchChainView(batch.batchNo(), batch.cropName(), tests, certificate, release);
    }

    // ============================ 内部辅助 ============================

    private SeedBatch lockExistingBatch(String batchNo) {
        return repository.lockBatch(batchNo)
                .flatMap(repository::findBatch)
                .orElseThrow(() -> new NotFoundException(RejectReason.BATCH_NOT_FOUND,
                        "批次" + batchNo + "不存在"));
    }

    private TestView toTestView(GerminationTest test) {
        return new TestView(test.testNo(), test.batchNo(), test.sampleCount(),
                test.germinatedCount(), test.ratioPercent(), test.status().name(),
                test.recordedAt(), test.voidedAt());
    }

    private CertificateView toCertificateView(GerminationCertificate c) {
        return new CertificateView(c.certNo(), c.batchNo(), c.firstTestNo(), c.firstRatio(),
                c.secondTestNo(), c.secondRatio(), c.status().name(), c.issuedAt());
    }

    // ============================ 幂等模板 ============================

    private enum Operation {
        REGISTER_TEST, VOID_TEST, ISSUE_CERTIFICATE, RELEASE_BATCH
    }

    /**
     * 幂等执行模板：同一 requestId 同参重放返回首次结果（成功或失败），异参返回 409。
     *
     * @param responseType 成功响应视图类型，用于反放首次响应 JSON
     * @param action       首次执行的业务动作
     */
    private <T> T runIdempotent(Operation operation, String requestId, String paramsHash,
                                Class<T> responseType, BusinessAction<T> action) {
        class Holder {
            T value;
            RuntimeException failure;
        }
        Holder holder = new Holder();
        txTemplate.executeWithoutResult(status -> {
            try {
                boolean acquired;
                try {
                    acquired = idempotentRepository.tryInsertProcessing(
                            requestId, operation.name(), paramsHash, clock.instant());
                } catch (DuplicateKeyException e) {
                    acquired = false;
                }
                if (!acquired) {
                    // 并发首请求可能仍在提交：行锁等待其结束后再判定。
                    IdempotentRecord record = idempotentRepository.findForUpdate(requestId)
                            .orElseThrow(() -> new DuplicateKeyException(
                                    "幂等记录在锁定后消失，需以原requestId重试"));
                    if (!record.operation().equals(operation.name())
                            || !record.paramsHash().equals(paramsHash)) {
                        throw new ConflictException(RejectReason.IDEMPOTENT_PARAM_MISMATCH,
                                "requestId=" + requestId + "已用于其他参数的请求，异参重放被拒绝");
                    }
                    holder.value = replay(record, responseType);
                    return;
                }
                try {
                    holder.value = action.run();
                    idempotentRepository.markDone(requestId,
                            writeJson(Map.of("kind", "SUCCESS", "body", holder.value)));
                } catch (RuntimeException ex) {
                    if (isCacheableFailure(ex)) {
                        idempotentRepository.markDone(requestId, writeJson(failureSnapshot(ex)));
                        holder.failure = ex;
                    } else {
                        throw ex;
                    }
                }
            } catch (DuplicateKeyException e) {
                // 首请求事务恰好回滚导致记录消失等竞态：整体回滚，允许原键重试。
                status.setRollbackOnly();
                holder.failure = new ConflictException(RejectReason.IDEMPOTENT_PARAM_MISMATCH,
                        "请求处理冲突，请以原requestId重试");
            }
        });
        if (holder.failure != null) {
            throw holder.failure;
        }
        return holder.value;
    }

    private boolean isCacheableFailure(RuntimeException ex) {
        return ex instanceof BusinessRejectedException
                || ex instanceof ConflictException
                || ex instanceof NotFoundException;
    }

    private Map<String, Object> failureSnapshot(RuntimeException ex) {
        int httpStatus;
        RejectReason reason;
        if (ex instanceof ConflictException conflict) {
            httpStatus = conflict.getStatus().value();
            reason = conflict.getReason();
        } else if (ex instanceof NotFoundException notFound) {
            httpStatus = notFound.getStatus().value();
            reason = notFound.getReason();
        } else if (ex instanceof BusinessRejectedException business) {
            httpStatus = 422;
            reason = business.getReason();
        } else {
            httpStatus = 500;
            reason = RejectReason.IDEMPOTENT_PARAM_MISMATCH;
        }
        return Map.of("kind", "FAILURE", "httpStatus", httpStatus,
                "reason", reason.name(), "message", ex.getMessage() == null ? "" : ex.getMessage());
    }

    private <T> T replay(IdempotentRecord record, Class<T> responseType) {
        JsonNode root = readJson(record.responseJson());
        String kind = root.path("kind").asText();
        if ("SUCCESS".equals(kind)) {
            return objectMapper.convertValue(root.get("body"), responseType);
        }
        RejectReason reason;
        try {
            reason = RejectReason.valueOf(root.path("reason").asText());
        } catch (IllegalArgumentException e) {
            reason = RejectReason.IDEMPOTENT_PARAM_MISMATCH;
        }
        throw new CachedResponseException(root.path("httpStatus").asInt(422), reason,
                root.path("message").asText("首次请求失败"));
    }

    @FunctionalInterface
    private interface BusinessAction<T> {
        T run();
    }

    // ============================ 参数哈希 ============================

    private Map<String, Object> operationParams(Operation operation, Object request) {
        Map<String, Object> params = new TreeMap<>();
        params.put("operation", operation.name());
        switch (operation) {
            case REGISTER_TEST -> {
                RegisterTestRequest r = (RegisterTestRequest) request;
                params.put("testNo", r.testNo());
                params.put("batchNo", r.batchNo());
                params.put("sampleCount", r.sampleCount());
                params.put("germinatedCount", r.germinatedCount());
            }
            case VOID_TEST -> {
                VoidTestRequest r = (VoidTestRequest) request;
                params.put("testNo", r.testNo());
                params.put("batchNo", r.batchNo());
            }
            case ISSUE_CERTIFICATE -> {
                IssueCertificateRequest r = (IssueCertificateRequest) request;
                params.put("batchNo", r.batchNo());
            }
            case RELEASE_BATCH -> {
                ReleaseBatchRequest r = (ReleaseBatchRequest) request;
                params.put("batchNo", r.batchNo());
            }
        }
        return params;
    }

    private String paramsOf(Map<String, Object> params) {
        return sha256(writeJson(params));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等参数序列化失败", e);
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前JVM不支持SHA-256", e);
        }
    }
}
