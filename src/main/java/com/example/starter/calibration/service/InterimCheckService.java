package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CreateInterimCheckRequest;
import com.example.starter.calibration.api.dto.InterimCheckResponse;
import com.example.starter.calibration.api.dto.IsolationIntervalResponse;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.model.CheckVerdict;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.InterimCheck;
import com.example.starter.calibration.model.IsolationInterval;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.InterimCheckRepository;
import com.example.starter.calibration.repo.IsolationIntervalRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.SuspectMarkingRepository;

/**
 * 仪器期间核查服务：提交核查（PASS/FAIL 判定、FAIL 追溯隔离、更晚 PASS 解除）、
 * 核查历史、隔离区间与受影响结果查询。写操作按 requestId 幂等。
 */
@Service
public class InterimCheckService {

    private final InterimCheckRepository checks;
    private final IsolationIntervalRepository intervals;
    private final SuspectMarkingRepository suspectMarkings;
    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;

    public InterimCheckService(InterimCheckRepository checks,
                               IsolationIntervalRepository intervals,
                               SuspectMarkingRepository suspectMarkings,
                               MeasurementRepository measurements,
                               CertificateRepository certificates,
                               ReleaseRepository releases) {
        this.checks = checks;
        this.intervals = intervals;
        this.suspectMarkings = suspectMarkings;
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
    }

    /**
     * 提交期间核查。|标准值-实测值|&lt;=容差判定 PASS，否则 FAIL：
     * FAIL 在同一事务内把追溯区间 [上一条 PASS 时刻（含）或最早测量时刻, 本次时刻（不含）)
     * 内已放行结果原子标记为 SUSPECT；更晚的 PASS 在同一事务内解除其覆盖区间内
     * 由这些 FAIL 引入、且不再被其他未解除 FAIL 覆盖的 SUSPECT 标记。
     * requestId 同参重放返回首次结果，异参 409；任何校验/冲突失败均回滚，不占用 requestId。
     */
    @Transactional
    public InterimCheckResponse submit(CreateInterimCheckRequest request) {
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String checkKey = Inputs.requireText(request.checkKey(), "checkKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant checkedAt = Inputs.requireInstant(request.checkedAt(), "checkedAt");
        BigDecimal standard = Inputs.requireDecimal(request.standardValue(), "standardValue");
        BigDecimal measured = Inputs.requireDecimal(request.measuredValue(), "measuredValue");
        BigDecimal tolerance = Inputs.requireDecimal(request.tolerance(), "tolerance");
        String checkedBy = Inputs.requireText(request.checkedBy(), "checkedBy");
        if (tolerance.signum() < 0) {
            throw ApiException.badRequest("tolerance 不能为负数");
        }

        String requestHash = hashRequest(checkKey, instrumentId, checkedAt,
                request.standardValue(), request.measuredValue(), request.tolerance(), checkedBy);

        // 幂等裁决：先对 requestId 行加锁串行化同 requestId 的并发写。
        var existingRequest = checks.findRequestForUpdate(requestId);
        if (existingRequest.isPresent()) {
            var record = existingRequest.get();
            if (!record.requestHash().equals(requestHash)) {
                throw ApiException.conflict("IDEMPOTENCY_PARAM_MISMATCH",
                        "requestId 已用于不同参数的核查: " + requestId);
            }
            InterimCheck first = checks.findByKey(record.checkKey())
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_STATE_MISSING",
                            "幂等首次结果缺失: " + record.checkKey()));
            return toResponse(first, true);
        }

        BigDecimal deviation = standard.subtract(measured).abs();
        CheckVerdict verdict = deviation.compareTo(tolerance) <= 0 ? CheckVerdict.PASS : CheckVerdict.FAIL;
        Instant now = Instant.now();

        InterimCheck check = new InterimCheck(0L, checkKey, instrumentId, checkedAt,
                standard, measured, tolerance, verdict, checkedBy, requestId, now);
        long checkId;
        try {
            checkId = checks.insert(check);
        } catch (DuplicateKeyException ex) {
            throw translateInsertConflict(checkKey, instrumentId, checkedAt);
        }

        if (verdict == CheckVerdict.FAIL) {
            applyFailIsolation(checkId, instrumentId, checkedAt, now);
        } else {
            resolveWithPass(checkId, instrumentId, checkedAt, now);
        }

        // 幂等登记与业务写入同一事务：业务失败回滚则不占键。
        try {
            checks.insertRequest(requestId, requestHash, checkKey, now);
        } catch (DuplicateKeyException ex) {
            // 并发同 requestId 首次写入落败：等待对方提交后按重放/异参裁决。
            var winner = checks.findRequestForUpdate(requestId);
            if (winner.isPresent()) {
                if (!winner.get().requestHash().equals(requestHash)) {
                    throw ApiException.conflict("IDEMPOTENCY_PARAM_MISMATCH",
                            "requestId 已用于不同参数的核查: " + requestId);
                }
                InterimCheck first = checks.findByKey(winner.get().checkKey()).orElseThrow();
                return toResponse(first, true);
            }
            throw ApiException.conflict("REQUEST_ID_CONFLICT", "requestId 冲突: " + requestId);
        }

        return toResponse(checks.findById(checkId).orElseThrow(), false);
    }

    /**
     * FAIL 追溯隔离：锁定区间内测量行使其与放行按提交顺序串行，
     * 写入隔离区间，并把区间内全部已放行结果原子标记为 SUSPECT。
     */
    private void applyFailIsolation(long checkId, String instrumentId, Instant checkedAt, Instant now) {
        Instant rangeFrom = checks.findLatestPassBefore(instrumentId, checkedAt)
                .map(InterimCheck::checkedAt)
                .orElseGet(() -> measurements.findEarliestMeasuredAt(instrumentId).orElse(checkedAt));

        // 区间内测量行锁使并发放行等待本事务提交后再按提交顺序裁决；
        // 区间与 SUSPECT 标记随后原子写入，回滚则全部不生效。
        measurements.findInRangeForUpdate(instrumentId, rangeFrom, checkedAt);
        intervals.insert(checkId, instrumentId, rangeFrom, checkedAt, now);
        suspectMarkings.markReleasedInRange(checkId, instrumentId, rangeFrom, checkedAt, now);
    }

    /**
     * 更晚 PASS 解除：锁定该 PASS 覆盖（FAIL 时刻不晚于本次 PASS）的全部未解除区间，
     * 原子写入解除信息，并仅清除不再被其他未解除 FAIL 覆盖的 SUSPECT 标记。
     */
    private void resolveWithPass(long passCheckId, String instrumentId, Instant checkedAt, Instant now) {
        List<IsolationInterval> resolved =
                intervals.findOpenResolvedByPassForUpdate(instrumentId, checkedAt);
        if (resolved.isEmpty()) {
            return;
        }
        List<Long> failCheckIds = resolved.stream().map(IsolationInterval::checkId).toList();
        for (IsolationInterval interval : resolved) {
            intervals.markResolved(interval.id(), passCheckId, now);
        }
        suspectMarkings.clearMarkings(failCheckIds, passCheckId, now);
    }

    /** 核查历史：可按仪器过滤，按核查时刻升序。 */
    @Transactional(readOnly = true)
    public List<InterimCheckResponse> history(String instrumentId) {
        String instrument = normalizeInstrument(instrumentId);
        return checks.findHistory(instrument).stream()
                .map(check -> toResponse(check, false))
                .toList();
    }

    /** 按核查键查询核查记录，不存在 404。 */
    @Transactional(readOnly = true)
    public InterimCheckResponse get(String checkKey) {
        String key = Inputs.requireText(checkKey, "checkKey");
        return toResponse(checks.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("核查不存在: " + key)), false);
    }

    /** 隔离区间历史：可按仪器、是否已解除过滤。 */
    @Transactional(readOnly = true)
    public List<IsolationIntervalResponse> intervals(String instrumentId, Boolean resolved) {
        String instrument = normalizeInstrument(instrumentId);
        return intervals.findIntervals(instrument, resolved).stream()
                .map(this::toIntervalResponse)
                .toList();
    }

    /** 某 FAIL 核查追溯区间内受影响的测量结果（区间内全部测量，含 SUSPECT 与待放行）。 */
    @Transactional(readOnly = true)
    public List<MeasurementResponse> affectedResults(String checkKey) {
        String key = Inputs.requireText(checkKey, "checkKey");
        InterimCheck check = checks.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("核查不存在: " + key));
        IsolationInterval interval = intervals.findByCheckId(check.id()).orElse(null);
        if (interval == null) {
            return List.of();
        }
        return measurements.findInRange(check.instrumentId(), interval.rangeFrom(), interval.rangeTo())
                .stream()
                .map(this::toMeasurementResponse)
                .toList();
    }

    private MeasurementResponse toMeasurementResponse(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        boolean suspect = suspectMarkings.hasActiveMarking(measurement.id());
        return DtoMapper.toResponse(measurement, certRevoked, suspect,
                releases.findByMeasurementId(measurement.id()));
    }

    private IsolationIntervalResponse toIntervalResponse(IsolationInterval interval) {
        String failCheckKey = checks.findById(interval.checkId())
                .map(InterimCheck::checkKey)
                .orElse(null);
        String resolvedByCheckKey = null;
        if (interval.resolvedByCheckId() != null) {
            resolvedByCheckKey = checks.findById(interval.resolvedByCheckId())
                    .map(InterimCheck::checkKey)
                    .orElse(null);
        }
        return new IsolationIntervalResponse(
                interval.id(),
                failCheckKey,
                interval.instrumentId(),
                interval.rangeFrom(),
                interval.rangeTo(),
                interval.resolvedByCheckId() != null,
                resolvedByCheckKey,
                interval.resolvedAt(),
                interval.createdAt());
    }

    private InterimCheckResponse toResponse(InterimCheck check, boolean replayed) {
        BigDecimal deviation = check.standardValue().subtract(check.measuredValue()).abs();
        return new InterimCheckResponse(
                check.id(),
                check.checkKey(),
                check.instrumentId(),
                check.checkedAt(),
                DtoMapper.format(check.standardValue()),
                DtoMapper.format(check.measuredValue()),
                DtoMapper.format(check.tolerance()),
                DtoMapper.format(deviation),
                check.verdict().name(),
                check.checkedBy(),
                check.requestId(),
                replayed,
                check.createdAt());
    }

    private ApiException translateInsertConflict(String checkKey, String instrumentId, Instant checkedAt) {
        if (checks.findByKey(checkKey).isPresent()) {
            return ApiException.conflict("DUPLICATE_CHECK_KEY", "核查键已存在: " + checkKey);
        }
        if (checks.findByInstrumentAndTime(instrumentId, checkedAt).isPresent()) {
            return ApiException.conflict("DUPLICATE_INSTRUMENT_CHECK_TIME",
                    "同一仪器同一核查时刻只允许一条核查记录");
        }
        return ApiException.conflict("CHECK_CONFLICT", "核查记录写入冲突");
    }

    private static String normalizeInstrument(String instrumentId) {
        return instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
    }

    /**
     * 归一化请求参数摘要：入参先按业务规则解析（十进制与 UTC 时刻归一），
     * 保证语义相同的输入摘要一致，语义不同的输入摘要不同。
     */
    private static String hashRequest(String checkKey, String instrumentId, Instant checkedAt,
                                      String standard, String measured, String tolerance,
                                      String checkedBy) {
        String canonical = String.join("",
                checkKey,
                instrumentId,
                checkedAt.toString(),
                new BigDecimal(standard.trim()).stripTrailingZeros().toPlainString(),
                new BigDecimal(measured.trim()).stripTrailingZeros().toPlainString(),
                new BigDecimal(tolerance.trim()).stripTrailingZeros().toPlainString(),
                checkedBy);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
