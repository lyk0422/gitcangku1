package com.example.starter.service;

import com.example.starter.domain.BatchRelease;
import com.example.starter.domain.CertificateStatus;
import com.example.starter.domain.GerminationCertificate;
import com.example.starter.domain.GerminationTest;
import com.example.starter.domain.SeedBatch;
import com.example.starter.domain.TestStatus;
import com.example.starter.repository.CertificateRepository;
import com.example.starter.repository.GerminationTestRepository;
import com.example.starter.repository.IdempotencyRepository;
import com.example.starter.repository.ReleaseRepository;
import com.example.starter.repository.SeedBatchRepository;
import com.example.starter.support.BusinessClock;
import com.example.starter.support.ConcurrencyHooks;
import com.example.starter.web.dto.Requests;
import com.example.starter.web.dto.Responses;
import com.example.starter.web.error.BusinessException;
import com.example.starter.web.error.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 种子发芽率证书业务事务实现。
 *
 * <p>并发控制：签发、作废、放行均先对批次行加 FOR UPDATE 行锁，按提交顺序串行；
 * 检测编号、证书编号、放行批次与幂等键另有数据库唯一约束兜底。
 * 幂等记录与业务写入同一事务：业务失败一并回滚，允许使用原 requestId 重试。
 */
@Service
public class CertificationServiceImpl implements CertificationService {

    /** 签发合格线：最近两次有效检测发芽率均须达到 85%。 */
    static final BigDecimal QUALIFIED_RATIO = new BigDecimal("0.8500");

    private final SeedBatchRepository batchRepository;
    private final GerminationTestRepository testRepository;
    private final CertificateRepository certificateRepository;
    private final ReleaseRepository releaseRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final BusinessClock clock;
    private final ObjectMapper objectMapper;
    private final ConcurrencyHooks concurrencyHooks;

    public CertificationServiceImpl(SeedBatchRepository batchRepository,
                                    GerminationTestRepository testRepository,
                                    CertificateRepository certificateRepository,
                                    ReleaseRepository releaseRepository,
                                    IdempotencyRepository idempotencyRepository,
                                    BusinessClock clock,
                                    ObjectMapper objectMapper,
                                    ConcurrencyHooks concurrencyHooks) {
        this.batchRepository = batchRepository;
        this.testRepository = testRepository;
        this.certificateRepository = certificateRepository;
        this.releaseRepository = releaseRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.concurrencyHooks = concurrencyHooks;
    }

    @Override
    @Transactional
    public Responses.BatchResponse createBatch(Requests.CreateBatchRequest request) {
        try {
            long id = batchRepository.insert(request.batchNo(), request.cropName(), clock.now());
            SeedBatch batch = batchRepository.findByBatchNo(request.batchNo())
                    .orElseThrow(() -> new IllegalStateException("批次插入后查询失败: id=" + id));
            return toBatchResponse(batch);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.BATCH_DUPLICATE,
                    "批次编号已存在: " + request.batchNo());
        }
    }

    @Override
    @Transactional
    public Responses.TestResponse registerTest(String batchNo, Requests.RegisterTestRequest request) {
        if (!request.germinatedNotExceedSample()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "发芽粒数不得大于样本数");
        }
        SeedBatch batch = requireBatch(batchNo);
        Optional<GerminationTest> existing = testRepository.findByTestNo(request.testNo());
        if (existing.isPresent()) {
            throw existing.get().batchId().equals(batch.id())
                    ? new BusinessException(ErrorCode.TEST_NO_DUPLICATE,
                            "检测编号已存在于本批次: " + request.testNo())
                    : new BusinessException(ErrorCode.CROSS_BATCH_REFERENCE,
                            "检测编号已归属其他批次: " + request.testNo());
        }
        try {
            testRepository.insert(request.testNo(), batch.id(), request.sampleSize(),
                    request.germinatedCount(),
                    request.testedAt() == null ? clock.now() : request.testedAt(),
                    clock.now());
        } catch (DuplicateKeyException e) {
            // 并发登记同一检测编号：数据库唯一约束保证一个编号只能归属一个批次。
            GerminationTest winner = testRepository.findByTestNo(request.testNo())
                    .orElseThrow(() -> e);
            throw winner.batchId().equals(batch.id())
                    ? new BusinessException(ErrorCode.TEST_NO_DUPLICATE,
                            "检测编号已存在于本批次: " + request.testNo())
                    : new BusinessException(ErrorCode.CROSS_BATCH_REFERENCE,
                            "检测编号已归属其他批次: " + request.testNo());
        }
        GerminationTest saved = testRepository.findByTestNo(request.testNo())
                .orElseThrow(() -> new IllegalStateException("检测记录插入后查询失败"));
        return toTestResponse(saved);
    }

    @Override
    @Transactional
    public Responses.TestResponse voidTest(String batchNo, String testNo) {
        SeedBatch batch = lockBatch(batchNo);
        GerminationTest test = testRepository.findByTestNo(testNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.TEST_NOT_FOUND,
                        "检测记录不存在: " + testNo));
        if (!test.batchId().equals(batch.id())) {
            throw new BusinessException(ErrorCode.CROSS_BATCH_REFERENCE,
                    "检测记录不属于该批次: " + testNo);
        }
        if (test.status() == TestStatus.VOID) {
            throw new BusinessException(ErrorCode.TEST_ALREADY_VOID,
                    "检测记录已作废，不能重复作废: " + testNo);
        }
        testRepository.markVoid(test.id(), clock.now());

        // 未放行批次：当前有效证书若依赖了本次作废的检测，证书不再满足两次有效检测，立即失效。
        // 已放行批次不回滚，保留当时证书快照与放行事实。
        boolean released = releaseRepository.findByBatchId(batch.id()).isPresent();
        if (!released) {
            certificateRepository.findLatestByBatchId(batch.id())
                    .filter(c -> c.status() == CertificateStatus.VALID)
                    .filter(c -> c.firstTestNo().equals(testNo) || c.secondTestNo().equals(testNo))
                    .ifPresent(c -> certificateRepository.markInvalid(c.id()));
        }
        concurrencyHooks.beforeVoidCommit(batchNo);

        GerminationTest updated = testRepository.findByTestNo(testNo)
                .orElseThrow(() -> new IllegalStateException("作废后检测记录查询失败"));
        return toTestResponse(updated);
    }

    @Override
    @Transactional
    public Responses.CertificateResponse issueCertificate(String batchNo,
                                                           Requests.IssueCertificateRequest request) {
        Optional<Responses.CertificateResponse> replayed =
                replayIfPresent(IdempotencyRepository.SCOPE_ISSUE, request.requestId(), batchNo,
                        Responses.CertificateResponse.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        SeedBatch batch = lockBatch(batchNo);
        // 加锁后再查一次，兜底并发下先提交的同键请求。
        Responses.CertificateResponse lockedReplay =
                replayIfPresent(IdempotencyRepository.SCOPE_ISSUE, request.requestId(), batchNo,
                        Responses.CertificateResponse.class)
                        .orElse(null);
        if (lockedReplay != null) {
            return lockedReplay;
        }

        List<GerminationTest> recent = testRepository.findRecentValid(batch.id(), 2);
        if (recent.size() < 2) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_VALID_TESTS,
                    "有效检测不足两次，无法签发证书");
        }
        GerminationTest second = recent.get(0);
        GerminationTest first = recent.get(1);
        if (first.ratio().compareTo(QUALIFIED_RATIO) < 0
                || second.ratio().compareTo(QUALIFIED_RATIO) < 0) {
            throw new BusinessException(ErrorCode.RATIO_NOT_QUALIFIED,
                    "最近两次有效检测发芽率未均达到85%，无法签发证书");
        }
        if (certificateRepository.existsByStatusAndTestNo(CertificateStatus.VALID, first.testNo())
                || certificateRepository.existsByStatusAndTestNo(
                        CertificateStatus.VALID, second.testNo())) {
            throw new BusinessException(ErrorCode.TEST_RECORD_REUSED,
                    "检测记录已被有效证书固化引用，不得重复使用");
        }

        String certificateNo = "CERT-" + batchNo + "-" + (certificateRepository.countByBatchId(batch.id()) + 1);
        GerminationCertificate certificate = new GerminationCertificate(
                null, certificateNo, batch.id(),
                first.testNo(), first.sampleSize(), first.germinatedCount(), first.ratio(),
                second.testNo(), second.sampleSize(), second.germinatedCount(), second.ratio(),
                CertificateStatus.VALID, request.requestId(), clock.now(), clock.now());
        try {
            certificateRepository.insert(certificate);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.TEST_RECORD_REUSED,
                    "检测记录已被有效证书固化引用，不得重复使用");
        }
        GerminationCertificate saved = certificateRepository.findLatestByBatchId(batch.id())
                .orElseThrow(() -> new IllegalStateException("证书插入后查询失败"));
        Responses.CertificateResponse response = toCertificateResponse(saved);
        saveIdempotentRecord(IdempotencyRepository.SCOPE_ISSUE, request.requestId(),
                batchNo, response);
        return response;
    }

    @Override
    @Transactional
    public Responses.ReleaseResponse release(String batchNo, Requests.ReleaseRequest request) {
        Optional<Responses.ReleaseResponse> replayed =
                replayIfPresent(IdempotencyRepository.SCOPE_RELEASE, request.requestId(), batchNo,
                        Responses.ReleaseResponse.class);
        if (replayed.isPresent()) {
            return replayed.get();
        }
        SeedBatch batch = lockBatch(batchNo);
        Responses.ReleaseResponse lockedReplay =
                replayIfPresent(IdempotencyRepository.SCOPE_RELEASE, request.requestId(), batchNo,
                        Responses.ReleaseResponse.class)
                        .orElse(null);
        if (lockedReplay != null) {
            return lockedReplay;
        }
        concurrencyHooks.afterReleaseLockAcquired(batchNo);

        if (releaseRepository.findByBatchId(batch.id()).isPresent()) {
            throw new BusinessException(ErrorCode.BATCH_ALREADY_RELEASED,
                    "批次已放行，不能重复放行");
        }
        GerminationCertificate certificate = certificateRepository.findLatestByBatchId(batch.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_VALID_CERTIFICATE,
                        "批次无有效证书，不能放行"));
        if (certificate.status() != CertificateStatus.VALID) {
            throw new BusinessException(ErrorCode.NO_VALID_CERTIFICATE,
                    "证书已失效，不能放行");
        }

        BatchRelease release = new BatchRelease(
                null, batch.id(), certificate.id(), certificate.certificateNo(),
                certificate.firstTestNo(), certificate.firstRatio(),
                certificate.secondTestNo(), certificate.secondRatio(),
                request.requestId(), clock.now(), clock.now());
        try {
            releaseRepository.insert(release);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.BATCH_ALREADY_RELEASED,
                    "批次已放行，不能重复放行");
        }
        BatchRelease saved = releaseRepository.findByBatchId(batch.id())
                .orElseThrow(() -> new IllegalStateException("放行事实插入后查询失败"));
        Responses.ReleaseResponse response = toReleaseResponse(saved);
        saveIdempotentRecord(IdempotencyRepository.SCOPE_RELEASE, request.requestId(),
                batchNo, response);
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public Responses.BatchChainResponse getChain(String batchNo) {
        SeedBatch batch = requireBatch(batchNo);
        List<Responses.TestResponse> tests = testRepository.findChainByBatchId(batch.id()).stream()
                .map(this::toTestResponse)
                .toList();
        Responses.CertificateResponse certificate =
                certificateRepository.findLatestByBatchId(batch.id())
                        .map(this::toCertificateResponse)
                        .orElse(null);
        Responses.ReleaseResponse release = releaseRepository.findByBatchId(batch.id())
                .map(this::toReleaseResponse)
                .orElse(null);
        return new Responses.BatchChainResponse(toBatchResponse(batch), tests, certificate, release);
    }

    private SeedBatch requireBatch(String batchNo) {
        return batchRepository.findByBatchNo(batchNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.BATCH_NOT_FOUND,
                        "批次不存在: " + batchNo));
    }

    private SeedBatch lockBatch(String batchNo) {
        Long id = batchRepository.lockAndGetId(batchNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.BATCH_NOT_FOUND,
                        "批次不存在: " + batchNo));
        return batchRepository.findByBatchNo(batchNo)
                .filter(b -> b.id().equals(id))
                .orElseThrow(() -> new BusinessException(ErrorCode.BATCH_NOT_FOUND,
                        "批次不存在: " + batchNo));
    }

    /**
     * 同 (scope, requestId) 的重放：参数指纹（批次编号）一致时还原首次响应；不一致返回 409。
     */
    private <T> Optional<T> replayIfPresent(String scope, String requestId, String batchNo,
                                            Class<T> responseType) {
        return idempotencyRepository.find(scope, requestId).map(record -> {
            if (!record.fingerprint().equals(batchNo)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "同一requestId已用于其他批次，请求参数不一致");
            }
            return readResponseJson(record.responseJson(), responseType);
        });
    }

    private void saveIdempotentRecord(String scope, String requestId, String batchNo, Object response) {
        try {
            idempotencyRepository.insert(scope, requestId, batchNo,
                    objectMapper.writeValueAsString(response), clock.now());
        } catch (DuplicateKeyException e) {
            // 并发同键请求：先提交者已写入，按重放/异参冲突处理，不产生第二份业务数据。
            IdempotencyRepository.IdempotencyRecord winner =
                    idempotencyRepository.find(scope, requestId).orElseThrow(() -> e);
            if (!winner.fingerprint().equals(batchNo)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "同一requestId已用于其他批次，请求参数不一致");
            }
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "同一requestId的并发请求已有首次响应");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private <T> T readResponseJson(String json, Class<T> responseType) {
        try {
            return objectMapper.readValue(json, responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private Responses.BatchResponse toBatchResponse(SeedBatch batch) {
        return new Responses.BatchResponse(batch.batchNo(), batch.cropName(), batch.createdAt());
    }

    private Responses.TestResponse toTestResponse(GerminationTest test) {
        return new Responses.TestResponse(
                test.testNo(), test.sampleSize(), test.germinatedCount(), test.ratio(),
                test.status(), test.testedAt(), test.voidedAt(), test.createdAt());
    }

    private Responses.CertificateResponse toCertificateResponse(GerminationCertificate c) {
        return new Responses.CertificateResponse(
                c.certificateNo(),
                c.status(),
                new Responses.CertTestSnapshot(
                        c.firstTestNo(), c.firstSampleSize(), c.firstGerminatedCount(), c.firstRatio()),
                new Responses.CertTestSnapshot(
                        c.secondTestNo(), c.secondSampleSize(), c.secondGerminatedCount(),
                        c.secondRatio()),
                c.issuedAt(),
                c.requestId());
    }

    private Responses.ReleaseResponse toReleaseResponse(BatchRelease r) {
        return new Responses.ReleaseResponse(
                r.certificateNo(), r.firstTestNo(), r.firstRatio(),
                r.secondTestNo(), r.secondRatio(), r.requestId(), r.releasedAt());
    }
}
