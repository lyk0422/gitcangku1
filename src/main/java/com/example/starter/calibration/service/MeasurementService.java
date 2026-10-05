package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CertificateNode;
import com.example.starter.calibration.model.CertificateType;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.Rational;
import com.example.starter.calibration.repo.CertificateNodeRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：提交（匹配唯一有效证书并冻结精确计算值与所用节点区间）、历史明细、当前可用结果查询。
 * 曲线插值结果以分数精确表示，合格判定基于分数本身，不先转有限小数。
 */
@Service
public class MeasurementService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final CertificateNodeRepository nodes;
    private final ReleaseRepository releases;
    private final IdempotencyService idempotency;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              CertificateNodeRepository nodes,
                              ReleaseRepository releases,
                              IdempotencyService idempotency) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.nodes = nodes;
        this.releases = releases;
        this.idempotency = idempotency;
    }

    /**
     * 提交测量（幂等入口）。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 曲线读数越界返回 422 且禁止外推；measurementKey 重复返回 409。
     */
    public MeasurementResponse submit(SubmitMeasurementRequest request) {
        return idempotency.execute(request.requestId(), "SUBMIT_MEASUREMENT", request.submittedBy(),
                "measurement:" + request.measurementKey(), request, request.expectedVersion(),
                () -> doSubmit(request), MeasurementResponse.class);
    }

    /**
     * 历史明细：包含原始测量、精确计算值、显示值与放行历史；不存在返回 404。
     * 计算值使用提交时冻结的分数与节点区间，不改用新证书重算。
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
     * 提交测量业务实现（在幂等事务内执行）。线性证书计算 a×读数+b；
     * 曲线证书在节点闭区间内按相邻节点线性插值，落在节点时直接取节点值。
     * 合格判断基于精确分数且包含端点。
     */
    private MeasurementResponse doSubmit(SubmitMeasurementRequest request) {
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

        Rational computed;
        Integer nodeLoIndex = null;
        Integer nodeHiIndex = null;
        if (cert.certType() == CertificateType.CURVE) {
            Interpolation interpolation = interpolate(nodes.findByCertificateId(cert.id()), reading);
            computed = interpolation.value();
            nodeLoIndex = interpolation.loIndex();
            nodeHiIndex = interpolation.hiIndex();
        } else {
            computed = Rational.of(cert.a().multiply(reading).add(cert.b()));
        }
        boolean passed = computed.compareTo(Rational.of(lower)) >= 0
                && computed.compareTo(Rational.of(upper)) <= 0;

        Measurement measurement = new Measurement(
                0L, key, instrumentId, measuredAt, reading, lower, upper, submittedBy,
                cert.id(), computed.num(), computed.den(), nodeLoIndex, nodeHiIndex,
                passed, MeasurementStatus.PENDING, Instant.now());
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 曲线插值：读数须落在首末节点闭区间内，越界抛出 422 且禁止外推；
     * 恰好落在节点上时直接取节点校正值，否则按相邻节点线性插值（分数精确）。
     */
    private Interpolation interpolate(List<CertificateNode> nodeList, BigDecimal reading) {
        CertificateNode first = nodeList.get(0);
        CertificateNode last = nodeList.get(nodeList.size() - 1);
        if (reading.compareTo(first.raw()) < 0 || reading.compareTo(last.raw()) > 0) {
            throw ApiException.unprocessable("READING_OUT_OF_RANGE",
                    "读数超出曲线节点闭区间 [" + first.raw() + ", " + last.raw() + "]，禁止外推");
        }
        for (CertificateNode node : nodeList) {
            if (reading.compareTo(node.raw()) == 0) {
                return new Interpolation(Rational.of(node.corrected()), node.nodeIndex(), node.nodeIndex());
            }
        }
        for (int i = 0; i < nodeList.size() - 1; i++) {
            CertificateNode lo = nodeList.get(i);
            CertificateNode hi = nodeList.get(i + 1);
            if (reading.compareTo(lo.raw()) > 0 && reading.compareTo(hi.raw()) < 0) {
                // value = corrLo + (corrHi - corrLo) × (reading - rawLo) / (rawHi - rawLo)
                Rational ratio = Rational.of(reading.subtract(lo.raw()))
                        .divide(Rational.of(hi.raw().subtract(lo.raw())));
                Rational value = Rational.of(lo.corrected())
                        .add(Rational.of(hi.corrected().subtract(lo.corrected())).multiply(ratio));
                return new Interpolation(value, lo.nodeIndex(), hi.nodeIndex());
            }
        }
        throw new IllegalStateException("曲线插值区间定位失败");
    }

    /**
     * 插值结果：精确分数与冻结的节点区间。
     */
    private record Interpolation(Rational value, int loIndex, int hiIndex) {
    }

    private MeasurementResponse toDetail(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()));
    }
}
