package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.EnvOutOfRangeException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CompensationCoefficient;
import com.example.starter.calibration.model.EnvRecord;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.CompensationCoefficientRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：提交（匹配唯一有效证书；记录环境时按型号当前系数版本计算线性环境补偿）、
 * 历史明细（原始值、补偿值、系数版本、重算链、放行历史）、当前可用结果查询。
 */
@Service
public class MeasurementService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final CompensationCoefficientRepository coefficients;
    private final IdempotentExecutor idempotency;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              ReleaseRepository releases,
                              CompensationCoefficientRepository coefficients,
                              IdempotentExecutor idempotency) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.coefficients = coefficients;
        this.idempotency = idempotency;
    }

    /**
     * 提交测量。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 使用 BigDecimal 精确计算 a×读数+b，基础合格判断基于未舍入值且包含端点。
     * 记录环境（型号+温度+湿度三者齐备）时：取型号当前生效系数版本，环境超出适用区间不得计算补偿，
     * 返回 422 并给出区间；区间内按线性公式计算补偿值（6 位小数）与补偿后值，并快照系数版本。
     * measurementKey 重复返回 409。calcKey 同键同指纹重放首次结果，失败不占键。
     */
    public MeasurementResponse submit(SubmitMeasurementRequest request) {
        ParsedSubmit parsed = parse(request);
        IdempotentExecutor.Result<MeasurementResponse> result = idempotency.execute(
                request.calcKey(), "SUBMIT",
                () -> doSubmit(parsed),
                resp -> parsed.fingerprintContent()
                        + versionFingerprintSuffix(resp.certificateId(), resp.coefficientId(), resp.versionNo()),
                MeasurementResponse.class);
        return result.value();
    }

    private ParsedSubmit parse(SubmitMeasurementRequest request) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant measuredAt = Inputs.requireInstant(request.measuredAt(), "measuredAt");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        BigDecimal uncertainty = Inputs.optionalDecimal(request.uncertainty(), "uncertainty");
        if (uncertainty != null) {
            Inputs.requireNonNegative(uncertainty, "uncertainty");
        }
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }
        String model = request.instrumentModel() == null ? null : request.instrumentModel().trim();
        BigDecimal temperature = request.temperatureC() == null || request.temperatureC().isBlank()
                ? null : Inputs.requireEnvDecimal(request.temperatureC(), "temperatureC");
        BigDecimal humidity = request.humidityPct() == null || request.humidityPct().isBlank()
                ? null : Inputs.requireEnvDecimal(request.humidityPct(), "humidityPct");
        int envParts = (model == null ? 0 : 1) + (temperature == null ? 0 : 1) + (humidity == null ? 0 : 1);
        if (envParts != 0 && envParts != 3) {
            throw ApiException.badRequest("instrumentModel、temperatureC、humidityPct 必须同时提供或同时省略");
        }
        if (humidity != null && (humidity.compareTo(BigDecimal.ZERO) < 0
                || humidity.compareTo(new BigDecimal("100")) > 0)) {
            throw ApiException.badRequest("humidityPct 必须在 0～100 之间");
        }
        EnvRecord env = model == null ? null : new EnvRecord(model, temperature, humidity);
        return new ParsedSubmit(key, instrumentId, measuredAt, reading, lower, upper,
                uncertainty, env, submittedBy, request.calcKey());
    }

    private record ParsedSubmit(String key, String instrumentId, Instant measuredAt, BigDecimal reading,
                              BigDecimal lower, BigDecimal upper, BigDecimal uncertainty, EnvRecord env,
                              String submittedBy, String calcKey) {

        String fingerprintContent() {
            return String.join("|",
                    "key=" + key,
                    "instrument=" + instrumentId,
                    "measuredAt=" + measuredAt,
                    "reading=" + reading.stripTrailingZeros().toPlainString(),
                    "lower=" + lower.stripTrailingZeros().toPlainString(),
                    "upper=" + upper.stripTrailingZeros().toPlainString(),
                    "uncertainty=" + (uncertainty == null ? "" : uncertainty.stripTrailingZeros().toPlainString()),
                    "env=" + (env == null ? "" : String.join(",",
                            env.instrumentModel(),
                            env.temperatureC().stripTrailingZeros().toPlainString(),
                            env.humidityPct().stripTrailingZeros().toPlainString())),
                    "submittedBy=" + submittedBy);
        }
    }

    /** 输入之外的版本化部分：证书 ID、系数版本 ID、版本号，追加在输入指纹之后。 */
    private String versionFingerprintSuffix(long certificateId, Long coefficientId, int versionNo) {
        return "|cert=" + certificateId
                + "|coefficient=" + (coefficientId == null ? "" : coefficientId)
                + "|version=" + versionNo;
    }

    private IdempotentExecutor.Outcome<MeasurementResponse> doSubmit(ParsedSubmit p) {
        Certificate cert = certificates.findMatching(p.instrumentId(), p.measuredAt())
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + p.instrumentId()));

        BigDecimal computed = cert.a().multiply(p.reading()).add(cert.b());
        boolean passed = computed.compareTo(p.lower()) >= 0 && computed.compareTo(p.upper()) <= 0;

        Long coefficientId = null;
        BigDecimal compensation = null;
        BigDecimal compensatedValue = null;
        Boolean passedAfterComp = null;
        if (p.env() != null) {
            // 先取型号行锁再读生效版本：系数版本发布与带环境提交按事务提交顺序裁决，
            // 新版本只对其提交之后的环境测量生效。
            coefficients.lockModel(p.env().instrumentModel());
            CompensationCoefficient coeff = coefficients.findActive(p.env().instrumentModel())
                    .orElseThrow(() -> ApiException.unprocessable(
                            "仪器型号无生效环境补偿系数版本: model=" + p.env().instrumentModel()));
            if (!coeff.covers(p.env().temperatureC(), p.env().humidityPct())) {
                throw new EnvOutOfRangeException(p.env().instrumentModel(),
                        coeff.tempMin(), coeff.tempMax(),
                        coeff.humidityMin(), coeff.humidityMax());
            }
            compensation = CompensationEngine.compensation(coeff, p.env());
            compensatedValue = CompensationEngine.compensated(computed, compensation);
            coefficientId = coeff.id();
            passedAfterComp = compensatedValue.compareTo(p.lower()) >= 0
                    && compensatedValue.compareTo(p.upper()) <= 0;
        }

        Measurement inserted = new Measurement(
                0L, 0L, 1, p.key(), p.instrumentId(),
                p.env() == null ? null : p.env().instrumentModel(),
                p.measuredAt(), p.reading(), p.lower(), p.upper(),
                null, p.env(), p.submittedBy(), cert.id(), coefficientId,
                computed, compensation, compensatedValue, p.uncertainty(),
                passed, passedAfterComp, MeasurementStatus.PENDING,
                null, null, null, Instant.now());
        long newId;
        try {
            newId = measurements.insert(inserted);
        } catch (DuplicateKeyException ex) {
            // 并发同 calcKey 重放：另一事务已先提交同版本测量，本事务回滚后由执行器重放首次结果。
            if (p.calcKey() != null) {
                throw new IdempotentConcurrentException("测量版本已由并发事务提交: " + p.key(), ex);
            }
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + p.key());
        }
        Measurement saved = measurements.findById(newId).orElseThrow();
        String fp = p.fingerprintContent()
                + versionFingerprintSuffix(saved.certificateId(), saved.coefficientId(), saved.versionNo());
        return IdempotentExecutor.Outcome.of(toDetail(saved), fp);
    }

    /**
     * 历史明细：原始测量、基础/补偿计算值、系数版本、重算链与放行历史；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        Measurement measurement = measurements.findLatestByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return toDetail(measurement);
    }

    /**
     * 当前可用结果：最新版本已放行且证书未撤销。instrumentId 为 null 时返回全部仪器。
     */
    @Transactional(readOnly = true)
    public List<MeasurementResponse> usable(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return measurements.findUsable(instrument).stream()
                .map(this::toDetail)
                .toList();
    }

    MeasurementResponse toDetail(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        Integer coeffVersion = measurement.coefficientId() == null ? null
                : coefficients.findById(measurement.coefficientId())
                        .map(CompensationCoefficient::versionNo).orElse(null);
        List<Measurement> chain = measurements.findChain(measurement.measurementKey());
        var chainSummaries = chain.stream()
                .map(m -> {
                    Integer v = m.coefficientId() == null ? null
                            : coefficients.findById(m.coefficientId())
                                    .map(CompensationCoefficient::versionNo).orElse(null);
                    return DtoMapper.toVersionSummary(m, v);
                })
                .toList();
        return DtoMapper.toResponse(measurement, certRevoked, coeffVersion,
                chainSummaries, releases.findByRootId(measurement.rootId()));
    }
}
