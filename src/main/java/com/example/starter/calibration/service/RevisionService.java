package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.dto.CreateRevisionRequest;
import com.example.starter.calibration.api.dto.MeasurementResponse;
import com.example.starter.calibration.api.dto.RevisionChainItem;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 修订服务：原提交人为每个 REJECTED 测量创建单一后继修订（保留证书和原始输入，
 * 仅允许修改测量值及说明），并提供修订链只读查询。
 */
@Service
public class RevisionService {

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;

    public RevisionService(MeasurementRepository measurements,
                           CertificateRepository certificates,
                           ReleaseRepository releases) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
    }

    /**
     * 为被驳回测量创建后继修订。前驱必须处于 REJECTED；仅原提交人可操作；
     * 每个前驱至多一个直接后继（predecessor_id 唯一），重复创建返回 409。
     * 后继重新计算结果，版本为前驱版本 +1（首个修订版本为 1）。
     */
    @Transactional
    public MeasurementResponse create(String key, CreateRevisionRequest request, String actor) {
        String submitter = Inputs.requireText(actor, "X-Actor-Id");
        String measurementKey = Inputs.requireText(key, "key");
        if (request.version() == null || request.version() < 0) {
            throw ApiException.badRequest("version 必须为不小于 0 的整数");
        }
        BigDecimal reading = Inputs.requireDecimal(request.reading(), "reading");
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();

        Measurement predecessor = measurements
                .findByKeyAndVersionForUpdate(measurementKey, request.version())
                .orElseThrow(() -> ApiException.notFound(
                        "测量不存在: " + measurementKey + " 版本 " + request.version()));
        if (predecessor.status() != MeasurementStatus.REJECTED) {
            throw ApiException.conflict("NOT_REJECTED",
                    "仅复核驳回的测量可创建后继修订: " + measurementKey + " 版本 " + request.version());
        }
        if (!predecessor.submittedBy().equals(submitter)) {
            throw ApiException.conflict("NOT_ORIGINAL_SUBMITTER", "仅原提交人可创建后继修订");
        }

        Certificate cert = certificates.findById(predecessor.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "测量关联的证书不存在: " + predecessor.certificateId()));
        BigDecimal computed = cert.a().multiply(reading).add(cert.b());
        boolean passed = computed.compareTo(predecessor.lowerLimit()) >= 0
                && computed.compareTo(predecessor.upperLimit()) <= 0;

        Measurement revision = new Measurement(
                0L, measurementKey, predecessor.version() + 1, predecessor.instrumentId(),
                predecessor.measuredAt(), reading, predecessor.lowerLimit(), predecessor.upperLimit(),
                predecessor.submittedBy(), predecessor.certificateId(), computed, passed,
                MeasurementStatus.PENDING, note, predecessor.id(), Instant.now());
        long id;
        try {
            id = measurements.insert(revision);
        } catch (DuplicateKeyException ex) {
            throw ApiException.conflict("REVISION_EXISTS",
                    "该测量已存在后继修订: " + measurementKey + " 版本 " + request.version());
        }
        return toDetail(measurements.findById(id).orElseThrow());
    }

    /**
     * 修订链只读查询：按版本升序返回该测量键的全部版本；键不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<RevisionChainItem> chain(String key) {
        List<Measurement> chain = measurements.findChain(key);
        if (chain.isEmpty()) {
            throw ApiException.notFound("测量不存在: " + key);
        }
        return chain.stream()
                .map(m -> new RevisionChainItem(m.id(), m.version(), m.status().name(),
                        DtoMapper.format(m.rawReading()), m.note(),
                        DtoMapper.format(m.computedValue()), m.displayValue().toPlainString(),
                        m.passed(), m.predecessorId(), m.createdAt()))
                .toList();
    }

    private MeasurementResponse toDetail(Measurement measurement) {
        boolean certRevoked = certificates.findById(measurement.certificateId())
                .map(Certificate::revoked)
                .orElse(true);
        return DtoMapper.toResponse(measurement, certRevoked,
                releases.hasActiveRelease(measurement.id()),
                releases.findByMeasurementId(measurement.id()));
    }
}
