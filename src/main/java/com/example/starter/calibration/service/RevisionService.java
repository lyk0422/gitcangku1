package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CreateRevisionRequest;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;

/**
 * 修订服务：原提交人为每个 REJECTED 测量创建单一后继修订。
 * 修订保留证书与原始输入，只允许修改测量值及说明；后继重新计算结果，版本从 1 开始。
 */
@Service
public class RevisionService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final MeasurementService measurementService;

    public RevisionService(MeasurementRepository measurements,
                           CertificateRepository certificates,
                           MeasurementService measurementService) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.measurementService = measurementService;
    }

    /**
     * 创建后继修订。前置条件：目标测量存在且处于 REJECTED、调用者即原提交人、
     * 该测量尚无后继修订（revision_of 唯一约束兜底并发）。修订键为 原键#r版本号。
     */
    @Transactional
    public MeasurementResponse create(CreateRevisionRequest request, String actor) {
        String submitter = Inputs.requireText(actor, "X-Actor-Id");
        String key = Inputs.requireText(request.measurementKey(), "measurementKey");
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        String note = Inputs.optionalNote(request.note(), "note", ReviewService.MAX_REASON_LENGTH);

        Measurement predecessor = measurements.findByKeyForUpdate(key)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + key));
        if (predecessor.status() != MeasurementStatus.REJECTED) {
            throw ApiException.conflict("NOT_REJECTED",
                    "仅复核驳回（REJECTED）的测量可创建后继修订: " + key);
        }
        if (!predecessor.submittedBy().equals(submitter)) {
            throw ApiException.forbidden("NOT_ORIGINAL_SUBMITTER",
                    "仅原提交人可创建后继修订: " + key);
        }
        if (measurements.findRevisionOf(predecessor.id()).isPresent()) {
            throw ApiException.conflict("REVISION_EXISTS",
                    "该测量已存在后继修订: " + key);
        }

        // 修订保留证书与原始输入（仪器、时刻、上下限、提交人），仅替换读数与说明
        Certificate cert = certificates.findById(predecessor.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "测量关联的证书不存在: " + predecessor.certificateId()));
        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        boolean passed = computed.compareTo(predecessor.lowerLimit()) >= 0
                && computed.compareTo(predecessor.upperLimit()) <= 0;

        long rootId = predecessor.rootId() == null ? predecessor.id() : predecessor.rootId();
        Measurement root = measurements.findById(rootId).orElseThrow();
        int version = measurements.findLatestRevision(rootId)
                .map(m -> m.version() + 1)
                .orElse(1);
        String revisionKey = root.measurementKey() + "#r" + version;

        Measurement revision = new Measurement(
                0L, revisionKey, predecessor.instrumentId(), predecessor.measuredAt(),
                reading, predecessor.lowerLimit(), predecessor.upperLimit(),
                predecessor.submittedBy(), predecessor.certificateId(), computed, passed,
                MeasurementStatus.PENDING, version, predecessor.id(), rootId, note, Instant.now());
        long id;
        try {
            id = measurements.insert(revision);
        } catch (DuplicateKeyException ex) {
            // 并发创建同一测量的后继修订：唯一约束兜底，仅一条成功
            throw ApiException.conflict("REVISION_EXISTS", "该测量已存在后继修订: " + key);
        }
        return measurementService.toDetail(measurements.findById(id).orElseThrow());
    }
}
