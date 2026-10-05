package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CreateDifferentialRequest;
import com.example.starter.calibration.api.dto.DifferentialActionRequest;
import com.example.starter.calibration.api.dto.DifferentialResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Differential;
import com.example.starter.calibration.model.DifferentialStatus;
import com.example.starter.calibration.model.IdempotencyRecord;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.DifferentialRepository;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 差分服务：创建（绑定两条未配对、同仪器、采样间隔不超过 10 秒且自身合格的测量）、
 * 放行（同时放行两条原测量并冻结双方证书与精确值）、取消（释放配对占用，记录保留）、查询。
 * 所有写操作以 requestId 幂等：身份指纹为操作+操作者+资源+全部参数与期望版本的结构化 JSON；
 * 幂等记录与业务写入同事务提交，业务失败不占键，唯一键竞争不泄露给调用方。
 */
@Service
public class DifferentialService {

    /** 参考/待测采样时刻允许的最大间隔（秒）。 */
    static final long MAX_SAMPLING_INTERVAL_SECONDS = 10L;

    private static final String OP_CREATE = "DIFF_CREATE";
    private static final String OP_RELEASE = "DIFF_RELEASE";
    private static final String OP_CANCEL = "DIFF_CANCEL";

    private final DifferentialRepository differentials;
    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final IdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;
    private final DifferentialService self;

    public DifferentialService(DifferentialRepository differentials,
                               MeasurementRepository measurements,
                               CertificateRepository certificates,
                               ReleaseRepository releases,
                               IdempotencyRepository idempotency,
                               ObjectMapper objectMapper,
                               @Lazy DifferentialService self) {
        this.differentials = differentials;
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    /**
     * 创建差分（入口）。唯一键竞争导致事务回滚后，按幂等记录/差分键/占用状态恢复为可区分响应。
     */
    public DifferentialResponse create(CreateDifferentialRequest request, String actor) {
        try {
            return self.createTx(request, actor);
        } catch (DuplicateKeyException ex) {
            return recoverCreateConflict(request, actor);
        }
    }

    /**
     * 创建差分（事务）。差值=待测校正值-参考校正值，按未舍入值判断闭区间限值；
     * 不合格仍保存待审差分但不能放行。配对只占用这两条测量，不改变原放行状态。
     */
    @Transactional
    public DifferentialResponse createTx(CreateDifferentialRequest request, String actor) {
        String creator = Inputs.requireText(actor, "X-Actor-Id");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String key = Inputs.requireText(request.differentialKey(), "differentialKey");
        String referenceKey = Inputs.requireText(request.referenceKey(), "referenceKey");
        String dutKey = Inputs.requireText(request.dutKey(), "dutKey");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }
        if (referenceKey.equals(dutKey)) {
            throw ApiException.badRequest("参考与待测测量键不能相同");
        }

        String fingerprint = fingerprint(OP_CREATE, creator, key,
                createParams(referenceKey, dutKey, lower, upper));
        Optional<IdempotencyRecord> existing = idempotency.find(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        // 先占幂等键：并发同键由唯一约束串行化；业务失败随事务回滚，不占键
        idempotency.insert(requestId, fingerprint);

        Measurement reference = measurements.findByKey(referenceKey)
                .orElseThrow(() -> ApiException.notFound("参考测量不存在: " + referenceKey));
        Measurement dut = measurements.findByKey(dutKey)
                .orElseThrow(() -> ApiException.notFound("待测测量不存在: " + dutKey));
        if (!reference.instrumentId().equals(dut.instrumentId())) {
            throw unprocessable("INSTRUMENT_MISMATCH", "参考与待测测量必须来自同一仪器");
        }
        Duration interval = Duration.between(reference.measuredAt(), dut.measuredAt()).abs();
        if (interval.compareTo(Duration.ofSeconds(MAX_SAMPLING_INTERVAL_SECONDS)) > 0) {
            throw unprocessable("SAMPLING_INTERVAL_EXCEEDED", "两条测量采样时刻相差超过 10 秒");
        }
        if (!reference.passed() || !dut.passed()) {
            throw unprocessable("MEASUREMENT_NOT_PASSED", "两条原测量自身必须合格");
        }
        Certificate referenceCert = certificates.findById(reference.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING", "参考测量关联的证书不存在"));
        Certificate dutCert = certificates.findById(dut.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING", "待测测量关联的证书不存在"));
        if (referenceCert.revoked() || dutCert.revoked()) {
            throw unprocessable("CERTIFICATE_REVOKED", "测量关联的证书已撤销");
        }
        if (differentials.isOccupied(reference.id()) || differentials.isOccupied(dut.id())) {
            throw ApiException.conflict("MEASUREMENT_ALREADY_PAIRED", "测量已被其他差分占用");
        }

        BigDecimal difference = dut.computedValue().subtract(reference.computedValue());
        boolean passed = difference.compareTo(lower) >= 0 && difference.compareTo(upper) <= 0;
        Differential differential = new Differential(
                0L, key, reference.id(), dut.id(), reference.instrumentId(),
                referenceCert.id(), dutCert.id(), difference, lower, upper, passed,
                DifferentialStatus.PENDING, creator, Instant.now(),
                null, null, null, null, null, null, 0L);
        long id = differentials.insert(differential);
        differentials.insertOccupation(id, reference.id());
        differentials.insertOccupation(id, dut.id());

        DifferentialResponse response = toResponse(differentials.findById(id).orElseThrow());
        idempotency.saveResponse(requestId, writeJson(response));
        return response;
    }

    /**
     * 创建唯一键竞争恢复（原事务已回滚）：同 requestId 同参重放、异参 409；
     * 否则区分差分键冲突与测量占用冲突。
     */
    private DifferentialResponse recoverCreateConflict(CreateDifferentialRequest request, String actor) {
        String creator = Inputs.requireText(actor, "X-Actor-Id");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String key = Inputs.requireText(request.differentialKey(), "differentialKey");
        String referenceKey = Inputs.requireText(request.referenceKey(), "referenceKey");
        String dutKey = Inputs.requireText(request.dutKey(), "dutKey");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        String fingerprint = fingerprint(OP_CREATE, creator, key,
                createParams(referenceKey, dutKey, lower, upper));
        Optional<IdempotencyRecord> existing = idempotency.find(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        if (differentials.findByKey(key).isPresent()) {
            throw ApiException.conflict("DUPLICATE_DIFFERENTIAL_KEY", "差分键已存在: " + key);
        }
        throw ApiException.conflict("MEASUREMENT_ALREADY_PAIRED", "测量已被其他差分占用");
    }

    /**
     * 放行差分（入口）。唯一键竞争恢复逻辑同创建。
     */
    public DifferentialResponse release(String key, DifferentialActionRequest request, String actor) {
        try {
            return self.releaseTx(key, request, actor);
        } catch (DuplicateKeyException ex) {
            return recoverActionConflict(OP_RELEASE, key, request, actor);
        }
    }

    /**
     * 放行差分（事务）：同时放行两条原测量并冻结双方证书与精确值；
     * 任一证书撤销或原测量已放行则整次 409；放行人不得是任一原测量提交人。
     * 行锁按 ID 升序获取，避免与批量放行/撤销并发时死锁。
     */
    @Transactional
    public DifferentialResponse releaseTx(String key, DifferentialActionRequest request, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String diffKey = Inputs.requireText(key, "differentialKey");
        String fingerprint = fingerprint(OP_RELEASE, releaser, diffKey, actionParams(request.expectedVersion()));
        Optional<IdempotencyRecord> existing = idempotency.find(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        idempotency.insert(requestId, fingerprint);

        Differential differential = differentials.findByKeyForUpdate(diffKey)
                .orElseThrow(() -> ApiException.notFound("差分不存在: " + diffKey));
        requirePending(differential, "已放行差分不能重复放行");
        requireVersion(differential, request.expectedVersion());
        if (!differential.passed()) {
            throw ApiException.conflict("NOT_PASSED", "差分不合格，不能放行");
        }

        Measurement[] pair = lockPair(differential.referenceMeasurementId(), differential.dutMeasurementId());
        Measurement reference = pair[0];
        Measurement dut = pair[1];
        if (reference.status() != MeasurementStatus.PENDING || dut.status() != MeasurementStatus.PENDING) {
            throw ApiException.conflict("MEASUREMENT_ALREADY_RELEASED", "原测量已放行，差分整次拒绝");
        }
        if (reference.submittedBy().equals(releaser) || dut.submittedBy().equals(releaser)) {
            throw ApiException.conflict("SAME_ACTOR", "放行人不得是任一原测量提交人");
        }
        Certificate[] certPair = lockCertificatePair(differential.referenceCertificateId(),
                differential.dutCertificateId());
        Certificate referenceCert = certPair[0];
        Certificate dutCert = certPair[1];
        if (referenceCert.revoked() || dutCert.revoked()) {
            throw ApiException.conflict("CERTIFICATE_REVOKED", "任一证书已撤销，差分整次拒绝");
        }

        Instant releasedAt = Instant.now();
        String batchId = "DIFF-" + diffKey;
        measurements.markReleased(reference.id());
        measurements.markReleased(dut.id());
        releases.insert(batchId, reference.id(), releaser, releasedAt);
        releases.insert(batchId, dut.id(), releaser, releasedAt);
        differentials.markReleased(differential.id(), releaser, releasedAt,
                reference.computedValue(), dut.computedValue());

        DifferentialResponse response = toResponse(differentials.findById(differential.id()).orElseThrow());
        idempotency.saveResponse(requestId, writeJson(response));
        return response;
    }

    /**
     * 取消差分（入口）。已放行差分不能取消；取消记录保留不删除，配对占用随之释放。
     */
    public DifferentialResponse cancel(String key, DifferentialActionRequest request, String actor) {
        try {
            return self.cancelTx(key, request, actor);
        } catch (DuplicateKeyException ex) {
            return recoverActionConflict(OP_CANCEL, key, request, actor);
        }
    }

    /**
     * 取消差分（事务）：仅放行前可取消；取消后释放两条测量的配对占用，记录保留。
     */
    @Transactional
    public DifferentialResponse cancelTx(String key, DifferentialActionRequest request, String actor) {
        String canceller = Inputs.requireText(actor, "X-Actor-Id");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String diffKey = Inputs.requireText(key, "differentialKey");
        String fingerprint = fingerprint(OP_CANCEL, canceller, diffKey, actionParams(request.expectedVersion()));
        Optional<IdempotencyRecord> existing = idempotency.find(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        idempotency.insert(requestId, fingerprint);

        Differential differential = differentials.findByKeyForUpdate(diffKey)
                .orElseThrow(() -> ApiException.notFound("差分不存在: " + diffKey));
        requirePending(differential, "已放行差分不能取消");
        requireVersion(differential, request.expectedVersion());

        differentials.markCancelled(differential.id(), canceller, Instant.now());
        differentials.deleteOccupation(differential.id());

        DifferentialResponse response = toResponse(differentials.findById(differential.id()).orElseThrow());
        idempotency.saveResponse(requestId, writeJson(response));
        return response;
    }

    /**
     * 差分明细：含未舍入差值、显示差值、放行/取消证据；证书撤销后仍可查。不存在返回 404。
     */
    @Transactional(readOnly = true)
    public DifferentialResponse detail(String key) {
        Differential differential = differentials.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("差分不存在: " + key));
        return toResponse(differential);
    }

    /**
     * 当前可用差分：已放行且双方证书均未撤销。instrumentId 为 null 时返回全部仪器。
     */
    @Transactional(readOnly = true)
    public List<DifferentialResponse> usable(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return differentials.findUsable(instrument).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 放行/取消唯一键竞争恢复（原事务已回滚）：同 requestId 同参重放、异参 409。
     */
    private DifferentialResponse recoverActionConflict(String operation, String key,
                                                       DifferentialActionRequest request, String actor) {
        String operator = Inputs.requireText(actor, "X-Actor-Id");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String diffKey = Inputs.requireText(key, "differentialKey");
        String fingerprint = fingerprint(operation, operator, diffKey, actionParams(request.expectedVersion()));
        Optional<IdempotencyRecord> existing = idempotency.find(requestId);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        throw ApiException.conflict("CONCURRENCY_CONFLICT", "并发写冲突，请重试");
    }

    private void requirePending(Differential differential, String releasedMessage) {
        if (differential.status() == DifferentialStatus.RELEASED) {
            throw ApiException.conflict("ALREADY_RELEASED", releasedMessage);
        }
        if (differential.status() == DifferentialStatus.CANCELLED) {
            throw ApiException.conflict("ALREADY_CANCELLED", "差分已取消: " + differential.differentialKey());
        }
    }

    private void requireVersion(Differential differential, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion.longValue() != differential.version()) {
            throw ApiException.conflict("VERSION_MISMATCH", "期望版本与当前版本不一致");
        }
    }

    private Measurement lockMeasurement(long id) {
        return measurements.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.conflict("MEASUREMENT_MISSING", "差分关联的测量不存在: " + id));
    }

    /**
     * 按 ID 升序锁定两条测量，返回 [参考, 待测]；固定加锁顺序避免并发死锁。
     */
    private Measurement[] lockPair(long referenceId, long dutId) {
        Measurement first = lockMeasurement(Math.min(referenceId, dutId));
        Measurement second = lockMeasurement(Math.max(referenceId, dutId));
        return referenceId <= dutId ? new Measurement[] {first, second}
                : new Measurement[] {second, first};
    }

    private Certificate lockCertificate(long id) {
        return certificates.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING", "差分关联的证书不存在: " + id));
    }

    /**
     * 按 ID 升序锁定双方证书（可能为同一证书），返回 [参考证书, 待测证书]。
     */
    private Certificate[] lockCertificatePair(long referenceCertId, long dutCertId) {
        if (referenceCertId == dutCertId) {
            Certificate cert = lockCertificate(referenceCertId);
            return new Certificate[] {cert, cert};
        }
        Certificate first = lockCertificate(Math.min(referenceCertId, dutCertId));
        Certificate second = lockCertificate(Math.max(referenceCertId, dutCertId));
        return referenceCertId <= dutCertId ? new Certificate[] {first, second}
                : new Certificate[] {second, first};
    }

    private Map<String, String> createParams(String referenceKey, String dutKey,
                                             BigDecimal lower, BigDecimal upper) {
        Map<String, String> params = new TreeMap<>();
        params.put("referenceKey", referenceKey);
        params.put("dutKey", dutKey);
        params.put("lowerLimit", lower.toPlainString());
        params.put("upperLimit", upper.toPlainString());
        params.put("expectedVersion", null);
        return params;
    }

    private Map<String, String> actionParams(Long expectedVersion) {
        Map<String, String> params = new TreeMap<>();
        params.put("expectedVersion", expectedVersion == null ? null : expectedVersion.toString());
        return params;
    }

    /**
     * 身份指纹：操作+操作者+资源+全部参数与期望版本的结构化 JSON（键有序），避免分隔符碰撞。
     */
    private String fingerprint(String operation, String actor, String resource, Map<String, String> params) {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("operation", operation);
        identity.put("actor", actor);
        identity.put("resource", resource);
        identity.put("params", params);
        return writeJson(identity);
    }

    private DifferentialResponse replayOrConflict(IdempotencyRecord record, String fingerprint) {
        if (record.fingerprint().equals(fingerprint)) {
            try {
                return objectMapper.readValue(record.responseBody(), DifferentialResponse.class);
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("幂等响应快照损坏", ex);
            }
        }
        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "requestId 已被不同参数的请求占用");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("响应序列化失败", ex);
        }
    }

    private ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private DifferentialResponse toResponse(Differential d) {
        Measurement reference = measurements.findById(d.referenceMeasurementId())
                .orElseThrow(() -> ApiException.conflict("MEASUREMENT_MISSING", "差分关联的测量不存在"));
        Measurement dut = measurements.findById(d.dutMeasurementId())
                .orElseThrow(() -> ApiException.conflict("MEASUREMENT_MISSING", "差分关联的测量不存在"));
        boolean referenceRevoked = certificates.findById(d.referenceCertificateId())
                .map(Certificate::revoked).orElse(true);
        boolean dutRevoked = certificates.findById(d.dutCertificateId())
                .map(Certificate::revoked).orElse(true);
        boolean usable = d.status() == DifferentialStatus.RELEASED && !referenceRevoked && !dutRevoked;
        return new DifferentialResponse(
                d.id(),
                d.differentialKey(),
                d.instrumentId(),
                reference.measurementKey(),
                dut.measurementKey(),
                d.referenceCertificateId(),
                d.dutCertificateId(),
                DtoMapper.format(d.difference()),
                d.displayDifference().toPlainString(),
                DtoMapper.format(d.lowerLimit()),
                DtoMapper.format(d.upperLimit()),
                d.passed(),
                d.status().name(),
                usable,
                d.version(),
                d.createdBy(),
                d.createdAt(),
                d.releasedBy(),
                d.releasedAt(),
                d.frozenReferenceValue() == null ? null : DtoMapper.format(d.frozenReferenceValue()),
                d.frozenDutValue() == null ? null : DtoMapper.format(d.frozenDutValue()),
                d.cancelledBy(),
                d.cancelledAt());
    }
}
