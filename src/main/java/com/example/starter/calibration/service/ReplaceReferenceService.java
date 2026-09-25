package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.RecomputedItem;
import com.example.starter.calibration.api.dto.ReplaceReferenceRequest;
import com.example.starter.calibration.api.dto.ReplaceReferenceResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.MeasurementVersionRepository;

/**
 * 替换标准器服务：对未放行批次整体切换到新的校准证书，生成新的测量版本并重算全部不确定度。
 * 任一测量重算失败则整批回滚（422），旧版本仍是当前有效版本，不留半成品状态。
 */
@Service
public class ReplaceReferenceService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final MeasurementVersionRepository versions;

    public ReplaceReferenceService(MeasurementRepository measurements,
                                   CertificateRepository certificates,
                                   MeasurementVersionRepository versions) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.versions = versions;
    }

    /**
     * 替换批次的标准器证书。批次不存在 404；批次含已放行测量 409；
     * 新证书不存在 404、已撤销 409；任一测量与新证书仪器不符或测量时刻超出证书有效期
     * 则整批 422 且旧版本保持当前有效。测量行锁与证书行锁保证与提交、撤销、放行按提交顺序裁决。
     */
    @Transactional
    public ReplaceReferenceResponse replace(String batchId, ReplaceReferenceRequest request) {
        String batch = Inputs.requireText(batchId, "batchId");
        if (request == null || request.certificateId() == null) {
            throw ApiException.badRequest("certificateId 不能为空");
        }
        long certificateId = request.certificateId();

        List<Measurement> batchMeasurements = measurements.findByBatchIdForUpdate(batch);
        if (batchMeasurements.isEmpty()) {
            throw ApiException.notFound("提交批次不存在: " + batch);
        }
        for (Measurement measurement : batchMeasurements) {
            if (measurement.status() == MeasurementStatus.RELEASED) {
                throw ApiException.conflict("BATCH_ALREADY_RELEASED",
                        "批次包含已放行测量，禁止替换标准器: " + batch);
            }
        }

        Certificate cert = certificates.findByIdForUpdate(certificateId)
                .orElseThrow(() -> ApiException.notFound("证书不存在: " + certificateId));
        if (cert.revoked()) {
            throw ApiException.conflict("CERTIFICATE_REVOKED", "证书已撤销: " + certificateId);
        }

        // 先整批预校验：任一测量无法重算则整批失败，不生成任何新版本
        List<ItemFailure> failures = new ArrayList<>();
        for (Measurement measurement : batchMeasurements) {
            List<String> reasons = new ArrayList<>();
            if (!cert.instrumentId().equals(measurement.instrumentId())) {
                reasons.add("INSTRUMENT_MISMATCH");
            } else if (measurement.measuredAt().isBefore(cert.validFrom())
                    || !measurement.measuredAt().isBefore(cert.validTo())) {
                reasons.add("CERTIFICATE_NOT_VALID_AT_MEASURED_AT");
            }
            if (!reasons.isEmpty()) {
                failures.add(new ItemFailure(measurement.measurementKey(), reasons));
            }
        }
        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures, HttpStatus.UNPROCESSABLE_ENTITY,
                    "RECOMPUTE_FAILED", "重算失败：旧版本仍为当前有效版本");
        }

        Instant now = Instant.now();
        List<RecomputedItem> recomputed = new ArrayList<>();
        for (Measurement measurement : batchMeasurements) {
            int newVersion = measurement.version() + 1;
            BigDecimal computed = MeasurementService.computed(cert, measurement.rawReading());
            BigDecimal uncertainty = MeasurementService.uncertainty(cert, measurement.rawReading());
            boolean passed = computed.compareTo(measurement.lowerLimit()) >= 0
                    && computed.compareTo(measurement.upperLimit()) <= 0;
            versions.insert(measurement.id(), newVersion, cert, computed, uncertainty, passed, now);
            measurements.applyVersion(measurement.id(), newVersion, cert, computed, uncertainty, passed);
            recomputed.add(new RecomputedItem(measurement.measurementKey(), newVersion));
        }
        return new ReplaceReferenceResponse(batch, recomputed);
    }
}
