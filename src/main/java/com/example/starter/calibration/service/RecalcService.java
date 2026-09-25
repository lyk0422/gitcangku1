package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.EnvOutOfRangeException;
import com.example.starter.calibration.api.dto.RecalcRequest;
import com.example.starter.calibration.api.dto.RecalcResponse;
import com.example.starter.calibration.api.dto.ReleaseDiagnosticItem;
import com.example.starter.calibration.model.CompensationCoefficient;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CompensationCoefficientRepository;
import com.example.starter.calibration.repo.MeasurementRepository;

/**
 * 重算服务：对未放行批次可提交重算。
 * 重算在一个事务内为目标逻辑测量用当前生效系数版本生成新测量版本（旧版本保留形成重算链，
 * 已放行结果及其系数快照不改写），并对整批重新进行环境补偿门禁评估（不自动放行）。
 */
@Service
public class RecalcService {

    private final MeasurementRepository measurements;
    private final CompensationCoefficientRepository coefficients;
    private final MeasurementService measurementService;
    private final ReleaseService releaseService;
    private final IdempotentExecutor idempotency;

    public RecalcService(MeasurementRepository measurements,
                          CompensationCoefficientRepository coefficients,
                          MeasurementService measurementService,
                          ReleaseService releaseService,
                          IdempotentExecutor idempotency) {
        this.measurements = measurements;
        this.coefficients = coefficients;
        this.measurementService = measurementService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
    }

    /** 校验后的重算请求。 */
    private record ParsedRecalc(String measurementKey, List<String> sortedBatchKeys,
                                 BigDecimal uncertaintyLimit, String calcKey) {
    }

    public RecalcResponse recalc(RecalcRequest request) {
        ParsedRecalc parsed = parse(request);
        IdempotentExecutor.Result<RecalcResponse> result = idempotency.execute(
                parsed.calcKey(), "RECALC",
                () -> doRecalc(parsed),
                resp -> replayFingerprint(parsed, resp),
                RecalcResponse.class);
        return result.value();
    }

    private ParsedRecalc parse(RecalcRequest request) {
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        String measurementKey = Inputs.requireText(request.measurementKey(), "measurementKey");
        List<String> sortedBatchKeys = releaseService.validateBatchKeys(request.batchKeys());
        if (!sortedBatchKeys.contains(measurementKey)) {
            throw ApiException.badRequest("batchKeys 必须包含 measurementKey");
        }
        BigDecimal limit = null;
        if (request.uncertaintyLimit() != null && !request.uncertaintyLimit().isBlank()) {
            limit = Inputs.requireDecimal(request.uncertaintyLimit(), "uncertaintyLimit");
            Inputs.requireNonNegative(limit, "uncertaintyLimit");
        }
        String calcKey = request.calcKey() == null || request.calcKey().isBlank()
                ? null : request.calcKey().trim();
        return new ParsedRecalc(measurementKey, sortedBatchKeys, limit, calcKey);
    }

    private IdempotentExecutor.Outcome<RecalcResponse> doRecalc(ParsedRecalc p) {
        // 先按字典序锁定整批测量版本行（与批量放行加锁顺序一致，避免交叉死锁），
        // 再锁型号行；目标行从锁定集合中取出，保证并发按事务提交顺序裁决。
        Measurement current = null;
        for (String key : p.sortedBatchKeys()) {
            Measurement locked = measurements.findLatestByKeyForUpdate(key)
                    .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
            if (key.equals(p.measurementKey())) {
                current = locked;
            }
        }
        if (current.status() == MeasurementStatus.RELEASED) {
            // 已放行结果及其系数快照不改写，不得重算。
            throw ApiException.conflict("ALREADY_RELEASED",
                    "测量已放行，不能重算: " + p.measurementKey());
        }

        BigDecimal computed = current.computedValue();
        Long coefficientId = null;
        BigDecimal compensation = null;
        BigDecimal compensatedValue = null;
        Boolean passedAfterComp = null;
        if (current.hasEnvironment()) {
            String model = current.instrumentModel();
            // 型号行锁使系数版本发布与重算按事务提交顺序裁决：重算只固化其提交时的生效版本。
            coefficients.lockModel(model);
            CompensationCoefficient coeff = coefficients.findActive(model)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "仪器型号无生效环境补偿系数版本: model=" + model));
            if (!coeff.covers(current.env().temperatureC(), current.env().humidityPct())) {
                throw new EnvOutOfRangeException(model,
                        coeff.tempMin(), coeff.tempMax(),
                        coeff.humidityMin(), coeff.humidityMax());
            }
            compensation = CompensationEngine.compensation(coeff, current.env());
            compensatedValue = CompensationEngine.compensated(computed, compensation);
            coefficientId = coeff.id();
            passedAfterComp = compensatedValue.compareTo(current.lowerLimit()) >= 0
                    && compensatedValue.compareTo(current.upperLimit()) <= 0;
        }

        Measurement next = new Measurement(
                0L, current.rootId(), current.versionNo() + 1,
                current.measurementKey(), current.instrumentId(), current.instrumentModel(),
                current.measuredAt(), current.rawReading(), current.lowerLimit(), current.upperLimit(),
                current.uncertaintyLimit(), current.env(), current.submittedBy(),
                current.certificateId(), coefficientId,
                computed, compensation, compensatedValue, current.uncertainty(),
                current.passed(), passedAfterComp, MeasurementStatus.PENDING,
                null, null, null, Instant.now());
        long newId = measurements.insert(next);
        Measurement saved = measurements.findById(newId).orElseThrow();

        // 重新评估整批（含新版本），不自动放行。
        List<ReleaseDiagnosticItem> diagnostics =
                releaseService.evaluateGateForRecalc(p.sortedBatchKeys(), p.uncertaintyLimit());

        RecalcResponse response = new RecalcResponse(
                saved.measurementKey(), saved.versionNo(),
                measurementService.toDetail(saved), diagnostics, Instant.now());
        return IdempotentExecutor.Outcome.of(response, fingerprint(p, saved, diagnostics));
    }

    /**
     * 重放指纹：基于已固化响应中的不可变版本行重建——目标取响应版本，批内各项取响应诊断中固化的版本号，
     * 避免后续重算产生更新版本后改变首次结果指纹。
     */
    private String replayFingerprint(ParsedRecalc p, RecalcResponse resp) {
        Measurement targetVersion = measurements
                .findByKeyAndVersion(p.measurementKey(), resp.versionNo())
                .orElseThrow(() -> ApiException.conflict("CALC_KEY_BUSY",
                        "calcKey 正被占用: " + p.calcKey()));
        List<ReleaseDiagnosticItem> diagnostics = resp.diagnostics().stream()
                .map(d -> pinnedGateItem(d, p.uncertaintyLimit()))
                .toList();
        return fingerprint(p, targetVersion, diagnostics);
    }

    private ReleaseDiagnosticItem pinnedGateItem(ReleaseDiagnosticItem d, BigDecimal uncertaintyLimit) {
        if (d.versionNo() == null) {
            return d;
        }
        Measurement m = measurements.findByKeyAndVersion(d.measurementKey(), d.versionNo()).orElse(null);
        if (m == null) {
            return d;
        }
        boolean passable = d.reasons().isEmpty();
        return new ReleaseDiagnosticItem(
                d.measurementKey(), m.versionNo(), m.status().name(),
                DtoMapper.format(m.releaseValue()),
                DtoMapper.format(m.lowerLimit()), DtoMapper.format(m.upperLimit()),
                DtoMapper.format(m.uncertainty()),
                uncertaintyLimit == null ? null : DtoMapper.format(uncertaintyLimit),
                m.hasEnvironment(), passable, d.reasons());
    }

    private String fingerprint(ParsedRecalc p, Measurement targetVersion,
                            List<ReleaseDiagnosticItem> diagnostics) {
        StringBuilder sb = new StringBuilder("target=").append(p.measurementKey())
                .append("|limit=").append(p.uncertaintyLimit() == null
                        ? "" : p.uncertaintyLimit().stripTrailingZeros().toPlainString())
                .append("|newvid=").append(targetVersion.id())
                .append(",version=").append(targetVersion.versionNo())
                .append(",coeff=").append(targetVersion.coefficientId() == null
                        ? "" : targetVersion.coefficientId());
        for (ReleaseDiagnosticItem d : diagnostics) {
            sb.append("|item=").append(d.measurementKey())
                    .append(",vid=").append(d.versionNo() == null ? "" : d.versionNo())
                    .append(",env=").append(d.hasEnvironment())
                    .append(",pass=").append(d.passable())
                    .append(",reasons=").append(String.join(";", d.reasons()));
        }
        return sb.toString();
    }
}
