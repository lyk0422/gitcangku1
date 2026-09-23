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
import com.example.starter.calibration.model.StandardVersionStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.DomainStateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.StandardVersionRepository;

/**
 * 测量服务：提交（匹配唯一有效证书、绑定当时有效的标准器版本并固化计算结果）、历史明细、当前可用结果查询。
 * 提交在领域版本行锁内串行化，与标准器血缘新增、失效激活按事务提交顺序生效。
 */
@Service
public class MeasurementService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final StandardVersionRepository versions;
    private final DomainStateRepository domainState;

    public MeasurementService(MeasurementRepository measurements,
                              CertificateRepository certificates,
                              ReleaseRepository releases,
                              StandardVersionRepository versions,
                              DomainStateRepository domainState) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.versions = versions;
        this.domainState = domainState;
    }

    /**
     * 提交测量。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 指定标准器版本时必须在测量时刻有效（VALID 且窗口覆盖），否则 422；
     * 使用 BigDecimal 精确计算 a×读数+b，合格判断基于未舍入值且包含端点。
     * measurementKey 重复返回 409（幂等键冲突）。
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

        // 领域版本行锁优先：与血缘新增、放行、失效激活按提交顺序串行化。
        domainState.currentVersionForUpdate();

        Certificate cert = certificates.findMatching(instrumentId, measuredAt)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + instrumentId));

        Long standardVersionId = null;
        if (request.standardVersionKey() != null && !request.standardVersionKey().isBlank()) {
            String versionKey = request.standardVersionKey().trim();
            StandardVersion version = versions.findByVersionKeyForUpdate(versionKey)
                    .orElseThrow(() -> ApiException.notFound("标准器版本不存在: " + versionKey));
            if (version.status() != StandardVersionStatus.VALID) {
                throw ApiException.unprocessable("标准器版本已失效，不得绑定: " + versionKey);
            }
            if (measuredAt.isBefore(version.validFrom()) || !measuredAt.isBefore(version.validTo())) {
                throw ApiException.unprocessable(
                        "测量时刻不在标准器版本有效窗口内: " + versionKey);
            }
            standardVersionId = version.id();
        }

        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        boolean passed = computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;

        Measurement measurement = new Measurement(
                0L, key, instrumentId, measuredAt, reading, lower, upper, submittedBy,
                cert.id(), standardVersionId, computed, passed, MeasurementStatus.PENDING, null,
                Instant.now());
        try {
            measurements.insert(measurement);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + key);
        }
        domainState.increment();
        return toDetail(measurements.findByKey(key).orElseThrow());
    }

    /**
     * 历史明细：包含原始测量、未舍入计算值、显示值与放行历史（失效冻结后仍保留原放行快照）；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return toDetail(measurement);
    }

    /**
     * 当前可用结果：已放行且证书未撤销。instrumentId 为 null 时返回全部仪器。
     * 已置 REVIEW_REQUIRED 的结果不再计入当前可用。
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
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.findByMeasurementId(measurement.id()));
    }
}
