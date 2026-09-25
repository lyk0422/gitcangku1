package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.ReviseMeasurementRequest;
import com.example.starter.calibration.api.dto.SubmitMeasurementRequest;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 测量服务：提交（匹配唯一有效证书并固化计算结果）、修订（产生新版本）、历史明细、当前可用结果查询。
 */
@Service
public class MeasurementService {

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
     * 提交测量（版本 1）。按测量时刻匹配唯一有效证书，无匹配返回 422；
     * 使用 BigDecimal 精确计算 a×读数+b，合格判断基于未舍入值且包含端点。
     * measurementKey 重复返回 409（幂等键冲突）。
     */
    @Transactional
    public MeasurementResponse submit(SubmitMeasurementRequest request) {
        Submission submission = validate(request.measurementKey(), request.instrumentId(),
                request.measuredAt(), request.reading(), request.lowerLimit(),
                request.upperLimit(), request.submittedBy());

        Measurement measurement = new Measurement(
                0L, submission.key, 1, submission.instrumentId, submission.measuredAt,
                submission.reading, submission.lower, submission.upper, submission.submittedBy,
                submission.certificate.id(), submission.computed, submission.passed,
                MeasurementStatus.PENDING, Instant.now());
        long id;
        try {
            id = measurements.insert(measurement);
            measurements.insertHead(submission.key, 1, id, Instant.now());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw ApiException.conflict("DUPLICATE_MEASUREMENT_KEY", "测量键已存在: " + submission.key);
        }
        return detail(submission.key);
    }

    /**
     * 修订测量：仅 RETURNED（待修订）状态可修订，产生同 measurementKey 的新版本；
     * 旧版本置为 SUPERSEDED，其复核仅保留历史、不迁移到新版本。放行后的测量不可修订。
     */
    @Transactional
    public MeasurementResponse revise(String key, ReviseMeasurementRequest request) {
        String measurementKey = Inputs.requireText(key, "measurementKey");
        Measurement current = measurements.findByKeyForUpdate(measurementKey)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + measurementKey));
        if (current.status() == MeasurementStatus.RELEASED) {
            throw ApiException.conflict("ALREADY_RELEASED", "测量已放行，不可修订: " + measurementKey);
        }
        if (current.status() != MeasurementStatus.RETURNED) {
            throw ApiException.conflict("NOT_RETURNED",
                    "测量未被同行复核退回（RETURN），不可修订: " + measurementKey);
        }
        Submission submission = validate(measurementKey, current.instrumentId(),
                current.measuredAt().toString(), request.reading(), request.lowerLimit(),
                request.upperLimit(), current.submittedBy());

        int newVersion = current.version() + 1;
        Measurement revised = new Measurement(
                0L, measurementKey, newVersion, submission.instrumentId, submission.measuredAt,
                submission.reading, submission.lower, submission.upper, submission.submittedBy,
                submission.certificate.id(), submission.computed, submission.passed,
                MeasurementStatus.PENDING, Instant.now());
        long newId = measurements.insert(revised);
        measurements.updateStatus(current.id(), MeasurementStatus.SUPERSEDED);
        measurements.updateHead(measurementKey, newVersion, newId, Instant.now());
        return detail(measurementKey);
    }

    /**
     * 历史明细：当前版本的原始测量、计算值、显示值与放行历史；不存在返回 404。
     */
    @Transactional(readOnly = true)
    public MeasurementResponse detail(String key) {
        Measurement measurement = measurements.findByKey(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        return toDetail(measurement);
    }

    /**
     * 当前可用结果：当前版本已放行且证书未撤销。instrumentId 为 null 时返回全部仪器。
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

    /**
     * 校验并计算一次提交/修订的测量值，固化匹配证书与未舍入判定结果。
     */
    private Submission validate(String key, String instrumentId, String measuredAt, String reading,
                                String lowerRaw, String upperRaw, String submittedBy) {
        String mKey = Inputs.requireText(key, "measurementKey");
        String instrument = Inputs.requireText(instrumentId, "instrumentId");
        Instant at = Inputs.requireInstant(measuredAt, "measuredAt");
        BigDecimal r = Inputs.requireDecimal(reading, "reading");
        BigDecimal lower = Inputs.requireDecimal(lowerRaw, "lowerLimit");
        BigDecimal upper = Inputs.requireDecimal(upperRaw, "upperLimit");
        String by = Inputs.requireText(submittedBy, "submittedBy");
        if (lower.compareTo(upper) > 0) {
            throw ApiException.badRequest("lowerLimit 不能大于 upperLimit");
        }
        Certificate cert = certificates.findMatching(instrument, at)
                .orElseThrow(() -> ApiException.unprocessable(
                        "测量时刻无匹配的有效证书: instrument=" + instrument));
        BigDecimal computed = cert.a().multiply(r).add(cert.b());
        boolean passed = computed.compareTo(lower) >= 0 && computed.compareTo(upper) <= 0;
        return new Submission(mKey, instrument, at, r, lower, upper, by, cert, computed, passed);
    }

    private record Submission(
            String key,
            String instrumentId,
            Instant measuredAt,
            BigDecimal reading,
            BigDecimal lower,
            BigDecimal upper,
            String submittedBy,
            Certificate certificate,
            BigDecimal computed,
            boolean passed) {
    }
}
