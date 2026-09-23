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
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.StandardVersion;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.StandardRepository;

/**
 * 测量服务：提交（匹配唯一有效证书并固化计算结果，可绑定当时有效的标准器版本）、
 * 历史明细、当前可用结果查询。
 */
@Service
public class MeasurementService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final StandardRepository standards;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              ReleaseRepository releases,
                              StandardRepository standards) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.standards = standards;
    }

    /**
     * 提交测量。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 使用 BigDecimal 精确计算 a×读数+b，合格判断基于未舍入值且包含端点。
     * measurementKey 重复返回 409（幂等键冲突）。
     * 提供 standardId 时须绑定当时有效（VALID 且窗口覆盖测量时刻）的标准器版本，无匹配返回 422；
     * 绑定在血缘全局锁内完成，与失效激活按事务提交顺序互斥。
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

        Long standardVersionId = null;
        if (request.standardId() != null && !request.standardId().isBlank()) {
            String standardId = request.standardId().trim();
            standards.lockLineage();
            StandardVersion standard = standards.findValidAt(standardId, measuredAt)
                    .orElseThrow(() -> ApiException.unprocessable(
                            "测量时刻无有效的标准器版本: standardId=" + standardId));
            standardVersionId = standard.id();
        }

        Certificate cert = certificates.findMatching(instrumentId, measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + instrumentId));

        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        boolean passed = computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;

        Measurement measurement = new Measurement(
                0L, key, instrumentId, measuredAt, reading, lower, upper, submittedBy,
                cert.id(), computed, passed, MeasurementStatus.PENDING,
                standardVersionId, null, null, Instant.now());
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 历史明细：包含原始测量、未舍入计算值、显示值与放行历史；不存在返回 404。
     * 受失效影响的记录显式返回 impactVersion 与冻结血缘路径，原数值与放行快照保留。
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

    private MeasurementResponse toDetail(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        String standardId = measurement.standardVersionId() == null ? null
                : standards.findById(measurement.standardVersionId())
                        .map(StandardVersion::standardId)
                        .orElse(null);
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()), standardId);
    }
}
