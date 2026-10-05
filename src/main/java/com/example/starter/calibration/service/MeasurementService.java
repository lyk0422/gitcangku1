package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CertificateType;
import com.example.starter.calibration.model.CurveNode;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：提交（匹配唯一有效证书并固化计算结果）、历史明细、当前可用结果查询。
 * 曲线测量按相邻节点线性插值，插值结果以既约分数精确比较上下限，不先转有限小数后判定。
 */
@Service
public class MeasurementService {

    /** 十进制缩放因子 10^6：读数、节点、上下限均最多 6 位小数，放大后为整数。 */
    private static final BigInteger SCALE = BigInteger.TEN.pow(6);

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              ReleaseRepository releases) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
    }

    /**
     * 提交测量。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 线性证书按 a×读数+b 精确计算；曲线证书按节点闭区间线性插值，越界 422 且禁止外推。
     * 合格判断基于未舍入精确值且包含端点。measurementKey 重复返回 409（幂等键冲突）。
     */
    @Transactional
    public MeasurementResponse submit(SubmitMeasurementRequest request) {
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        String instrumentId = Inputs.requireText(request.instrumentId(), "instrumentId");
        Instant measuredAt = Inputs.requireInstant(request.measuredAt(), "measuredAt");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        BigDecimal lower = Inputs.requireDecimal(request.lowerLimit(), "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(request.upperLimit(), "upperLimit");
        String submittedBy = Inputs.requireText(request.submittedBy(), "submittedBy");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }

        Certificate cert = certificates.findMatching(instrumentId, measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + instrumentId));

        Measurement measurement;
        if (cert.certType() == CertificateType.CURVE) {
            measurement = buildCurveMeasurement(key, instrumentId, measuredAt, reading, lower, upper,
                    submittedBy, cert);
        } else {
            BigDecimal computed = cert.a().multiply(reading).add(cert.b());
            boolean passed = computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;
            measurement = new Measurement(
                    0L, key, instrumentId, measuredAt, reading, lower, upper, submittedBy,
                    cert.id(), CertificateType.LINEAR, computed, null, null,
                    null, null, null, null, passed, MeasurementStatus.PENDING, Instant.now());
        }
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 历史明细：包含原始测量、未舍入精确值、显示值与放行历史；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return toDetail(measurement);
    }

    /**
     * 当前可用结果：已放行且证书未撤销。instrumentId 为 null 时返回全部仪器。
     */
    @Transactional(readOnly = true)
    public List<MeasurementResponse> usable(String instrumentId) {
        String instrument = instrumentId == null || instrumentId.isBlank() ? null : instrumentId.trim();
        return measurements.findUsable(instrument).stream()
                .map(this::toDetail)
                .toList();
    }

    /**
     * 构造曲线测量：读数落在节点闭区间内按相邻节点线性插值，落在节点时直接取节点值；
     * 插值结果以既约分数保存并精确比较上下限；冻结所用节点及区间。
     */
    private Measurement buildCurveMeasurement(String key, String instrumentId, Instant measuredAt,
                                              BigDecimal reading, BigDecimal lower, BigDecimal upper,
                                              String submittedBy, Certificate cert) {
        List<CurveNode> nodes = certificates.findNodes(cert.id());
        BigInteger r = scaled(reading);
        if (r.compareTo(scaled(nodes.get(0).raw())) < 0
                || r.compareTo(scaled(nodes.get(nodes.size() - 1).raw())) > 0) {
            throw ApiException.outOfRange("读数超出曲线节点闭区间 [" + nodes.get(0).raw().toPlainString()
                    + ", " + nodes.get(nodes.size() - 1).raw().toPlainString() + "]，禁止外推");
        }

        CurveNode lo = null;
        CurveNode hi = null;
        for (int i = 0; i < nodes.size(); i++) {
            BigInteger x = scaled(nodes.get(i).raw());
            if (x.equals(r)) {
                lo = hi = nodes.get(i);
                break;
            }
            if (x.compareTo(r) > 0) {
                hi = nodes.get(i);
                lo = nodes.get(i - 1);
                break;
            }
        }

        // 精确分数：值 = num / den；节点命中时为 corrected/1，区间内为线性插值既约分数
        BigInteger num;
        BigInteger den;
        if (lo == hi) {
            num = scaled(lo.corrected());
            den = SCALE;
        } else {
            BigInteger x1 = scaled(lo.raw());
            BigInteger x2 = scaled(hi.raw());
            BigInteger y1 = scaled(lo.corrected());
            BigInteger y2 = scaled(hi.corrected());
            num = y1.multiply(x2.subtract(x1)).add(r.subtract(x1).multiply(y2.subtract(y1)));
            den = x2.subtract(x1).multiply(SCALE);
        }
        BigInteger gcd = num.gcd(den);
        if (gcd.compareTo(BigInteger.ONE) > 0) {
            num = num.divide(gcd);
            den = den.divide(gcd);
        }

        // 以分数精确比较：num/den ∈ [lower, upper] ⟺ lower×10^6×den ≤ num×10^6 ≤ upper×10^6×den
        BigInteger scaledNum = num.multiply(SCALE);
        boolean passed = scaledNum.compareTo(scaled(lower).multiply(den)) >= 0
                && scaledNum.compareTo(scaled(upper).multiply(den)) <= 0;

        return new Measurement(
                0L, key, instrumentId, measuredAt, reading, lower, upper, submittedBy,
                cert.id(), CertificateType.CURVE, null, num.toString(), den.toString(),
                lo.raw(), lo.corrected(), hi.raw(), hi.corrected(),
                passed, MeasurementStatus.PENDING, Instant.now());
    }

    /** 十进制（最多 6 位小数）放大 10^6 为整数。 */
    private static BigInteger scaled(BigDecimal value) {
        return value.movePointRight(6).toBigIntegerExact();
    }

    private MeasurementResponse toDetail(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()));
    }
}
