package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CheckResponse;
import com.example.starter.calibration.api.dto.InterimCheckView;
import com.example.starter.calibration.api.dto.IsolationIntervalView;
import com.example.starter.calibration.api.dto.SubmitCheckRequest;
import com.example.starter.calibration.model.CheckResult;
import com.example.starter.calibration.model.InterimCheck;
import com.example.starter.calibration.model.IsolationInterval;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.InterimCheckRepository;
import com.example.starter.calibration.repo.IsolationIntervalRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.RequestRecordRepository;
import com.example.starter.calibration.repo.RequestRecordRepository.RequestRecord;
import com.example.starter.calibration.repo.SuspectMarkerRepository;

/**
 * 仪器期间核查服务。
 *
 * <p>判定：|实测值 - 标准值| &lt;= 容差为 PASS，否则 FAIL。
 *
 * <p>FAIL 时在同一事务内确定追溯区间（上一条 PASS 时刻含，或最早测量时刻；到本次时刻不含），
 * 把区间内全部已放行结果原子标记 SUSPECT，区间内待放行结果随后由放行流程拒绝（409）。
 *
 * <p>隔离只能由核查时刻更晚的 PASS 解除：一个事务内清除其覆盖区间内由该 FAIL 引入的 SUSPECT，
 * 仅恢复不再被其他未解除 FAIL 覆盖且证书未撤销的结果。
 *
 * <p>核查与测量提交、批量放行、证书撤销通过 instrument_lock 仪器级行锁按提交顺序裁决。
 */
@Service
public class CheckService {

    /** request_record 中本操作类型标识。 */
    static final String OPERATION = "INTERIM_CHECK";

    private final InterimCheckRepository checks;
    private final IsolationIntervalRepository intervals;
    private final SuspectMarkerRepository markers;
    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final RequestRecordRepository requests;

    public CheckService(InterimCheckRepository checks,
                        IsolationIntervalRepository intervals,
                        SuspectMarkerRepository markers,
                        MeasurementRepository measurements,
                        CertificateRepository certificates,
                        RequestRecordRepository requests) {
        this.checks = checks;
        this.intervals = intervals;
        this.markers = markers;
        this.measurements = measurements;
        this.certificates = certificates;
        this.requests = requests;
    }

    /**
     * 提交期间核查。
     *
     * <p>requestId 幂等：同参重放返回首次结果；异参返回 409；校验/业务失败不写入 request_record（失败不占键）。
     * checkKey 重复或同一仪器同一时刻重复返回 409。
     */
    @Transactional
    public CheckResponse submit(SubmitCheckRequest request) {
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        String checkKey = Inputs.requireText(request.checkKey(), "checkKey");
        if (requestId.length() > 96) {
            throw ApiException.badRequest("requestId 长度不能超过 96");
        }
        if (checkKey.length() > 64) {
            throw ApiException.badRequest("checkKey 长度不能超过 64");
        }
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant checkedAt = Inputs.requireInstant(request.checkedAt(), "checkedAt");
        BigDecimal standard = Inputs.requireDecimal(request.standardValue(), "standardValue");
        BigDecimal actual = Inputs.requireDecimal(request.actualValue(), "actualValue");
        BigDecimal tolerance = Inputs.requireDecimal(request.tolerance(), "tolerance");
        String checkedBy = Inputs.requireText(request.checkedBy(), "checkedBy");
        if (tolerance.signum() < 0) {
            throw ApiException.badRequest("tolerance 不能为负");
        }
        String fingerprint = fingerprint(instrumentId, checkedAt, standard, actual, tolerance, checkedBy);

        // 幂等判定须在仪器锁外先看已提交记录；命中同参直接回放，异参冲突。
        var existing = requests.findById(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, checkKey);
        }

        // 仪器级行锁：与测量提交、批量放行、证书撤销按提交顺序串行裁决。
        certificates.lockInstrument(instrumentId);

        // 双重检查：拿到锁后再确认 requestId（并发同 requestId 时由唯一约束兜底）。
        existing = requests.findById(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, checkKey);
        }

        BigDecimal deviation = actual.subtract(standard).abs();
        CheckResult result = deviation.compareTo(tolerance) <= 0 ? CheckResult.PASS : CheckResult.FAIL;
        Instant now = Instant.now();

        InterimCheck check = new InterimCheck(
                0L, checkKey, instrumentId, checkedAt, standard, actual, tolerance,
                result, checkedBy, now);
        long checkId;
        try {
            checkId = checks.insert(check);
        } catch (DuplicateKeyException ex) {
            // checkKey 全局唯一，或 (instrument_id, checked_at) 同一仪器同一时刻唯一
            throw ApiException.conflict("DUPLICATE_CHECK",
                    "核查已存在：checkKey 重复或同一仪器同一核查时刻已存在记录");
        }

        CheckResponse response;
        if (result == CheckResult.FAIL) {
            response = applyFail(checkId, check, deviation);
        } else {
            response = applyPass(checkId, check, deviation);
        }

        try {
            requests.insert(requestId, OPERATION, fingerprint, checkKey, now);
        } catch (DuplicateKeyException ex) {
            // 并发同 requestId 已由另一事务成功提交：当前事务必然与之冲突，抛出 409 由调用方回放。
            throw ApiException.conflict("IDEMPOTENCY_RACE", "requestId 已被并发请求占用: " + requestId);
        }
        return response;
    }

    /**
     * FAIL 处理：确定追溯区间、原子标记 SUSPECT、写区间与逐结果标记。
     */
    private CheckResponse applyFail(long checkId, InterimCheck check, BigDecimal deviation) {
        Instant rangeFrom = checks.findLatestPassAtBefore(check.instrumentId(), check.checkedAt())
                .orElseGet(() -> measurements.findEarliestMeasuredAt(check.instrumentId())
                        .orElse(check.checkedAt()));
        Instant rangeTo = check.checkedAt();

        // 区间内全部已放行结果原子标记 SUSPECT（status 保持 RELEASED，放行历史不动）。
        measurements.markSuspectInInterval(check.instrumentId(), rangeFrom, rangeTo);
        List<Long> releasedIds = measurements.findReleasedIdsInInterval(
                check.instrumentId(), rangeFrom, rangeTo);
        Instant now = Instant.now();
        for (long measurementId : releasedIds) {
            markers.insert(measurementId, checkId, now);
        }
        intervals.insert(checkId, check.checkKey(), check.instrumentId(), rangeFrom, rangeTo, now);

        List<String> blockedPendingKeys = measurements.findPendingKeysInInterval(
                check.instrumentId(), rangeFrom, rangeTo);

        return new CheckResponse(
                check.checkKey(), check.instrumentId(), check.checkedAt(),
                DtoMapper.format(check.standardValue()), DtoMapper.format(check.actualValue()),
                DtoMapper.format(check.tolerance()), DtoMapper.format(deviation),
                CheckResult.FAIL.name(), check.checkedBy(), check.createdAt(), false,
                rangeFrom, rangeTo,
                measurements.findKeysByIds(releasedIds), blockedPendingKeys,
                List.of(), List.of());
    }

    /**
     * PASS 处理：解除所有终点不晚于本次核查时刻的未解除区间，清除对应 SUSPECT，
     * 仅恢复不再被其他未解除 FAIL 覆盖且证书未撤销的结果。
     */
    private CheckResponse applyPass(long passCheckId, InterimCheck pass, BigDecimal deviation) {
        List<IsolationInterval> resolved = intervals.findOpenForPass(
                pass.instrumentId(), pass.checkedAt());
        Instant now = Instant.now();
        List<Long> candidateMeasurementIds = new ArrayList<>();
        List<String> resolvedCheckKeys = new ArrayList<>();
        for (IsolationInterval interval : resolved) {
            intervals.markResolved(interval.id(), passCheckId, pass.checkKey(), now);
            markers.clearByCheck(interval.checkId(), passCheckId, now);
            candidateMeasurementIds.addAll(markers.findMeasurementIdsByCheck(interval.checkId()));
            resolvedCheckKeys.add(interval.checkKey());
        }
        List<Long> distinctIds = candidateMeasurementIds.stream().distinct().sorted().toList();

        List<String> restoredKeys = new ArrayList<>();
        for (long measurementId : distinctIds) {
            // 仅当不存在其他未清除 FAIL 标记时才可能恢复；证书撤销导致的不可用不受影响。
            measurements.clearSuspectIfNoOpenMarker(measurementId);
            var loaded = measurements.findById(measurementId);
            if (loaded.isPresent()) {
                var m = loaded.get();
                boolean certRevoked = certificates.findById(m.certificateId())
                        .map(c -> c.revoked()).orElse(true);
                if (!m.suspect() && !certRevoked) {
                    restoredKeys.add(m.measurementKey());
                }
            }
        }

        return new CheckResponse(
                pass.checkKey(), pass.instrumentId(), pass.checkedAt(),
                DtoMapper.format(pass.standardValue()), DtoMapper.format(pass.actualValue()),
                DtoMapper.format(pass.tolerance()), DtoMapper.format(deviation),
                CheckResult.PASS.name(), pass.checkedBy(), pass.createdAt(), false,
                null, null, List.of(), List.of(),
                List.copyOf(resolvedCheckKeys), List.copyOf(restoredKeys));
    }

    /**
     * requestId 已存在时的回放/冲突处理。
     */
    private CheckResponse replay(RequestRecord record, String fingerprint, String requestCheckKey) {
        if (!record.fingerprint().equals(fingerprint) || !record.checkKey().equals(requestCheckKey)) {
            throw ApiException.conflict("IDEMPOTENCY_PARAM_MISMATCH",
                    "requestId 已提交但参数不一致: " + record.requestId());
        }
        InterimCheck check = checks.findByKey(record.checkKey())
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_STATE_MISSING",
                        "幂等记录指向的核查不存在: " + record.checkKey()));
        return buildResponse(check, true);
    }

    private CheckResponse buildResponse(InterimCheck check, boolean replayed) {
        if (check.result() == CheckResult.FAIL) {
            List<IsolationInterval> its = intervals.findByCheckKey(check.checkKey());
            IsolationInterval interval = its.isEmpty() ? null : its.get(0);
            List<String> affectedKeys = its.isEmpty()
                    ? List.of()
                    : measurements.findKeysByIds(markers.findMeasurementIdsByCheck(its.get(0).checkId()));
            return new CheckResponse(
                    check.checkKey(), check.instrumentId(), check.checkedAt(),
                    DtoMapper.format(check.standardValue()), DtoMapper.format(check.actualValue()),
                    DtoMapper.format(check.tolerance()),
                    DtoMapper.format(check.actualValue().subtract(check.standardValue()).abs()),
                    CheckResult.FAIL.name(), check.checkedBy(), check.createdAt(), replayed,
                    interval == null ? null : interval.rangeFrom(),
                    interval == null ? null : interval.rangeTo(),
                    affectedKeys, List.of(), List.of(), List.of());
        }
        return new CheckResponse(
                check.checkKey(), check.instrumentId(), check.checkedAt(),
                DtoMapper.format(check.standardValue()), DtoMapper.format(check.actualValue()),
                DtoMapper.format(check.tolerance()),
                DtoMapper.format(check.actualValue().subtract(check.standardValue()).abs()),
                CheckResult.PASS.name(), check.checkedBy(), check.createdAt(), replayed,
                null, null, List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 核查明细：不存在返回 404。
     */
    @Transactional(readOnly = true)
    public CheckResponse detail(String checkKey) {
        InterimCheck check = checks.findByKey(checkKey)
                .orElseThrow(() -> ApiException.notFound("核查不存在: " + checkKey));
        return buildResponse(check, false);
    }

    /**
     * 核查历史：instrumentId 为空时返回全部，按核查时刻升序。
     */
    @Transactional(readOnly = true)
    public List<InterimCheckView> history(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return checks.findHistory(instrument).stream()
                .map(c -> new InterimCheckView(
                        c.checkKey(), c.instrumentId(), c.checkedAt(),
                        DtoMapper.format(c.standardValue()), DtoMapper.format(c.actualValue()),
                        DtoMapper.format(c.tolerance()), c.result().name(), c.checkedBy(), c.createdAt()))
                .toList();
    }

    /**
     * 隔离区间历史（含受影响结果键）：instrumentId 为空时返回全部。
     */
    @Transactional(readOnly = true)
    public List<IsolationIntervalView> intervalHistory(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return intervals.findHistory(instrument).stream()
                .map(i -> new IsolationIntervalView(
                        i.checkKey(), i.instrumentId(), i.rangeFrom(), i.rangeTo(), i.resolved(),
                        i.resolvedByCheckKey(), i.resolvedAt(),
                        measurements.findKeysByIds(markers.findMeasurementIdsByCheck(i.checkId()))))
                .toList();
    }

    /**
     * 归一化请求参数指纹（SHA-256），用于同参/异参判定。
     */
    static String fingerprint(String instrumentId, Instant checkedAt, BigDecimal standard,
                              BigDecimal actual, BigDecimal tolerance, String checkedBy) {
        String normalized = String.join("|",
                instrumentId,
                checkedAt.toString(),
                standard.stripTrailingZeros().toPlainString(),
                actual.stripTrailingZeros().toPlainString(),
                tolerance.stripTrailingZeros().toPlainString(),
                checkedBy);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
