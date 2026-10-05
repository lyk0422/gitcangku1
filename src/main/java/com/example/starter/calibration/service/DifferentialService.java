package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CreateDifferentialRequest;
import com.example.starter.calibration.api.dto.DifferentialResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Differential;
import com.example.starter.calibration.model.DifferentialStatus;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.DifferentialRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 差分服务：创建（绑定同一仪器、采样时刻相差不超过 10 秒的两条未占用测量）、
 * 放行（同时放行两条原测量并冻结双方证书与精确值）、放行前取消（释放占用，记录保留）。
 * 所有写操作经 requestId 幂等：同键同参重放首次完整响应，异参 409，失败不占键。
 */
@Service
public class DifferentialService {

    /** 参考与待测采样时刻允许的最大间隔。 */
    static final Duration MAX_SAMPLE_INTERVAL = Duration.ofSeconds(10);

    private final DifferentialRepository differentials;
    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final IdempotencyGuard idempotency;

    public DifferentialService(DifferentialRepository differentials,
                               MeasurementRepository measurements,
                               CertificateRepository certificates,
                               ReleaseRepository releases,
                               IdempotencyGuard idempotency) {
        this.differentials = differentials;
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.idempotency = idempotency;
    }

    /**
     * 创建差分记录。要求：两条测量存在且不同、同仪器、UTC 采样时刻相差不超过 10 秒、
     * 自身均合格、均待放行、未被其他差分占用。差值 = 待测校正值 - 参考校正值，
     * 合格判定使用未舍入差值且包含端点；不合格仍保存待审差分但不能放行。
     */
    @Transactional
    public DifferentialResponse create(String requestId, String actor, CreateDifferentialRequest request) {
        String creator = Inputs.requireText(actor, "X-Actor-Id");
        String referenceKey = Inputs.requireText(request.referenceKey(), "referenceKey");
        String dutKey = Inputs.requireText(request.dutKey(), "dutKey");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }

        Map<String, Object> params = new TreeMap<>();
        params.put("referenceKey", referenceKey);
        params.put("dutKey", dutKey);
        params.put("lowerLimit", lower.toPlainString());
        params.put("upperLimit", upper.toPlainString());
        String rid = Inputs.requireText(requestId, "X-Request-Id");
        String fingerprint = idempotency.fingerprint("DIFF_CREATE", creator, "differentials", params);
        if (!idempotency.tryAcquire(rid, fingerprint)) {
            return idempotency.replay(rid, fingerprint, DifferentialResponse.class);
        }

        Measurement reference = measurements.findByKeyForUpdate(referenceKey)
                .orElseThrow(() -> ApiException.notFound("参考测量不存在: " + referenceKey));
        Measurement dut = measurements.findByKeyForUpdate(dutKey)
                .orElseThrow(() -> ApiException.notFound("待测测量不存在: " + dutKey));
        if (reference.id() == dut.id()) {
            throw ApiException.badRequest("参考与待测不能是同一条测量");
        }
        if (!reference.instrumentId().equals(dut.instrumentId())) {
            throw ApiException.unprocessable("INSTRUMENT_MISMATCH", "参考与待测测量必须来自同一仪器");
        }
        Duration interval = Duration.between(reference.measuredAt(), dut.measuredAt()).abs();
        if (interval.compareTo(MAX_SAMPLE_INTERVAL) > 0) {
            throw ApiException.unprocessable("SAMPLING_INTERVAL_EXCEEDED",
                    "参考与待测采样时刻相差超过 10 秒");
        }
        if (!reference.passed() || !dut.passed()) {
            throw ApiException.unprocessable("MEASUREMENT_NOT_PASSED", "两条原测量自身必须均合格");
        }
        if (reference.status() != MeasurementStatus.PENDING || dut.status() != MeasurementStatus.PENDING) {
            throw ApiException.conflict("MEASUREMENT_ALREADY_RELEASED", "原测量已放行，不能绑定差分");
        }
        if (differentials.isOccupied(reference.id()) || differentials.isOccupied(dut.id())) {
            throw ApiException.conflict("MEASUREMENT_OCCUPIED", "测量已被其他差分占用");
        }

        BigDecimal diff = dut.computedValue().subtract(reference.computedValue());
        boolean passed = diff.compareTo(lower) >= 0 && diff.compareTo(upper) <= 0;
        Differential differential = new Differential(
                0L, reference.instrumentId(), reference.id(), dut.id(),
                reference.certificateId(), dut.certificateId(),
                reference.computedValue(), dut.computedValue(), diff, lower, upper,
                passed, DifferentialStatus.PENDING, 1, creator, Instant.now(),
                null, null, null, null);
        long id = differentials.insert(differential);
        try {
            differentials.insertOccupation(reference.id(), id, differential.createdAt());
            differentials.insertOccupation(dut.id(), id, differential.createdAt());
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("MEASUREMENT_OCCUPIED", "测量已被其他差分占用");
        }
        DifferentialResponse response = toResponse(differentials.findById(id).orElseThrow());
        idempotency.complete(rid, response);
        return response;
    }

    /**
     * 放行差分：同时放行两条原测量并写入放行历史。要求：差分待审且合格、
     * 两条原测量仍待放行、双方证书均未撤销、放行人不同于任一原测量提交人。
     * 任一条件不满足则整次 409，不产生部分放行。
     */
    @Transactional
    public DifferentialResponse release(String requestId, String actor, long id, Long expectedVersion) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        String rid = Inputs.requireText(requestId, "X-Request-Id");
        String fingerprint = idempotency.fingerprint("DIFF_RELEASE", releaser, "differential/" + id,
                versionParams(expectedVersion));
        if (!idempotency.tryAcquire(rid, fingerprint)) {
            return idempotency.replay(rid, fingerprint, DifferentialResponse.class);
        }

        Differential differential = differentials.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("差分记录不存在: " + id));
        requirePending(differential);
        requireVersion(differential, expectedVersion);
        if (!differential.passed()) {
            throw ApiException.conflict("DIFF_NOT_PASSED", "差分不合格，不能放行");
        }

        Measurement reference = lockMeasurement(differential.referenceMeasurementId());
        Measurement dut = lockMeasurement(differential.dutMeasurementId());
        if (reference.status() != MeasurementStatus.PENDING || dut.status() != MeasurementStatus.PENDING) {
            throw ApiException.conflict("MEASUREMENT_ALREADY_RELEASED", "原测量已放行，差分不能放行");
        }
        Certificate referenceCert = lockCertificate(differential.referenceCertificateId());
        Certificate dutCert = lockCertificate(differential.dutCertificateId());
        if (referenceCert.revoked() || dutCert.revoked()) {
            throw ApiException.conflict("CERTIFICATE_REVOKED", "差分关联的证书已撤销");
        }
        if (reference.submittedBy().equals(releaser) || dut.submittedBy().equals(releaser)) {
            throw ApiException.conflict("SAME_ACTOR", "放行人不得是任一原测量提交人");
        }

        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        measurements.markReleased(reference.id());
        measurements.markReleased(dut.id());
        releases.insert(batchId, reference.id(), releaser, releasedAt);
        releases.insert(batchId, dut.id(), releaser, releasedAt);
        differentials.markReleased(id, releaser, releasedAt);
        DifferentialResponse response = toResponse(differentials.findById(id).orElseThrow());
        idempotency.complete(rid, response);
        return response;
    }

    /**
     * 取消差分：仅放行前允许；记录保留不删除，占用随之释放。已放行差分不能取消。
     */
    @Transactional
    public DifferentialResponse cancel(String requestId, String actor, long id, Long expectedVersion) {
        String canceller = Inputs.requireText(actor, "X-Actor-Id");
        String rid = Inputs.requireText(requestId, "X-Request-Id");
        String fingerprint = idempotency.fingerprint("DIFF_CANCEL", canceller, "differential/" + id,
                versionParams(expectedVersion));
        if (!idempotency.tryAcquire(rid, fingerprint)) {
            return idempotency.replay(rid, fingerprint, DifferentialResponse.class);
        }

        Differential differential = differentials.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("差分记录不存在: " + id));
        requirePending(differential);
        requireVersion(differential, expectedVersion);

        differentials.markCancelled(id, canceller, Instant.now());
        differentials.deleteOccupations(id);
        DifferentialResponse response = toResponse(differentials.findById(id).orElseThrow());
        idempotency.complete(rid, response);
        return response;
    }

    /**
     * 差分明细：包含冻结的精确值、显示差值与当前可用性；不存在返回 404。
     * 撤销任一证书后差分当前不可用，旧配对及放行证据仍可查。
     */
    @Transactional(readOnly = true)
    public DifferentialResponse detail(long id) {
        return toResponse(differentials.findById(id)
                .orElseThrow(() -> ApiException.notFound("差分记录不存在: " + id)));
    }

    private Map<String, Object> versionParams(Long expectedVersion) {
        Map<String, Object> params = new TreeMap<>();
        params.put("expectedVersion", expectedVersion);
        return params;
    }

    private void requirePending(Differential differential) {
        if (differential.status() == DifferentialStatus.RELEASED) {
            throw ApiException.conflict("ALREADY_RELEASED", "差分已放行: " + differential.id());
        }
        if (differential.status() == DifferentialStatus.CANCELLED) {
            throw ApiException.conflict("ALREADY_CANCELLED", "差分已取消: " + differential.id());
        }
    }

    private void requireVersion(Differential differential, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != differential.version()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "期望版本不一致: expected=" + expectedVersion + ", actual=" + differential.version());
        }
    }

    private Measurement lockMeasurement(long measurementId) {
        return measurements.findByIdForUpdate(measurementId)
                .orElseThrow(() -> ApiException.conflict("MEASUREMENT_MISSING",
                        "差分关联的测量不存在: " + measurementId));
    }

    private Certificate lockCertificate(long certificateId) {
        return certificates.findByIdForUpdate(certificateId)
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "差分关联的证书不存在: " + certificateId));
    }

    private DifferentialResponse toResponse(Differential differential) {
        Measurement reference = measurements.findById(differential.referenceMeasurementId())
                .orElseThrow(() -> ApiException.conflict("MEASUREMENT_MISSING",
                        "差分关联的测量不存在: " + differential.referenceMeasurementId()));
        Measurement dut = measurements.findById(differential.dutMeasurementId())
                .orElseThrow(() -> ApiException.conflict("MEASUREMENT_MISSING",
                        "差分关联的测量不存在: " + differential.dutMeasurementId()));
        boolean referenceRevoked = certificates.findById(differential.referenceCertificateId())
                .map(Certificate::revoked).orElse(true);
        boolean dutRevoked = certificates.findById(differential.dutCertificateId())
                .map(Certificate::revoked).orElse(true);
        return DtoMapper.toResponse(differential, reference.measurementKey(), dut.measurementKey(),
                referenceRevoked, dutRevoked);
    }
}
