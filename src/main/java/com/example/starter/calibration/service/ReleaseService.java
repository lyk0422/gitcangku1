package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.ReleaseDiagnosticsItem;
import com.example.starter.calibration.api.dto.ReleaseDiagnosticsResponse;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseBatch;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseBatchRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效；任一项不满足条件则整批拒绝并返回各项原因。
 * 放行门禁：待放行、判定合格、证书未撤销、证书未过期、追溯字段完整、
 * singleBatchOnly 证书未绑定其他批次（绑定冲突返回 422 并携带已绑定批次）。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final ReleaseBatchRepository releaseBatches;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases,
                          ReleaseBatchRepository releaseBatches) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.releaseBatches = releaseBatches;
    }

    /**
     * 原子批量放行。每条结果必须：处于待放行、判定合格、证书未撤销且未过期、
     * 标准器/补偿系数/不确定度版本完整可追溯、放行人不同于提交人；
     * singleBatchOnly 证书首次被引用时绑定本批次，已被其他批次绑定则整批 422。
     * 行锁按测量键字典序获取，避免并发批次间死锁；证书行锁使撤销与放行按事务提交顺序生效。
     * 任一证书过期、撤销或绑定冲突时整批放行状态不变。
     */
    @Transactional
    public ReleaseResponse release(List<String> keys, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量放行包含重复测量键");
        }

        Instant releasedAt = Instant.now();
        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> approved = new ArrayList<>();
        List<Long> bindingCandidates = new ArrayList<>();
        boolean bindingConflict = false;
        for (String key : orderedKeys.stream().sorted().toList()) {
            var locked = measurements.findByKeyForUpdate(key);
            if (locked.isEmpty()) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            Measurement measurement = locked.get();
            List<String> reasons = new ArrayList<>();
            String boundBatchId = null;
            if (measurement.status() == MeasurementStatus.RELEASED) {
                reasons.add("ALREADY_RELEASED");
            } else if (measurement.status() != MeasurementStatus.PENDING) {
                reasons.add("NOT_PENDING");
            }
            if (!measurement.passed()) {
                reasons.add("NOT_PASSED");
            }
            Certificate cert = certificates.findByIdForUpdate(measurement.certificateId())
                    .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                            "测量关联的证书不存在: " + measurement.certificateId()));
            if (cert.revoked()) {
                reasons.add("CERTIFICATE_REVOKED");
            } else if (!releasedAt.isBefore(cert.validTo())) {
                reasons.add("CERTIFICATE_EXPIRED");
            }
            if (measurement.certVersion() == null || measurement.compensationCoeff() == null
                    || measurement.uncertaintyVersion() == null) {
                reasons.add("MISSING_TRACEABILITY");
            }
            if (cert.singleBatchOnly()) {
                if (cert.boundBatchId() != null) {
                    reasons.add("CERTIFICATE_BOUND_TO_OTHER_BATCH");
                    boundBatchId = cert.boundBatchId();
                    bindingConflict = true;
                } else {
                    bindingCandidates.add(cert.id());
                }
            }
            if (measurement.submittedBy().equals(releaser)) {
                reasons.add("SAME_ACTOR");
            }
            if (reasons.isEmpty()) {
                approved.add(measurement);
            } else {
                failures.add(new ItemFailure(key, reasons, boundBatchId));
            }
        }

        if (!failures.isEmpty()) {
            HttpStatus status = bindingConflict ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CONFLICT;
            throw new BatchRejectedException(failures, status, "BATCH_REJECTED",
                    "批量放行被拒绝：存在不满足放行条件的测量");
        }

        String batchId = UUID.randomUUID().toString();
        for (Long certificateId : bindingCandidates.stream().distinct().toList()) {
            int updated = certificates.bindBatch(certificateId, batchId);
            if (updated != 1) {
                // 持有证书行锁期间不会发生；防御并发实现变化导致的绑定丢失
                throw ApiException.conflict("CERTIFICATE_BOUND_TO_OTHER_BATCH",
                        "证书已被其他批次绑定: " + certificateId);
            }
        }
        releaseBatches.insert(batchId, releaser, releasedAt, approved.size());
        for (Measurement measurement : approved) {
            measurements.markReleased(measurement.id());
            releases.insert(batchId, measurement.id(), releaser, releasedAt);
        }
        return new ReleaseResponse(batchId, releaser, releasedAt, orderedKeys);
    }

    /**
     * 放行诊断：批次头与逐条测量的标准器、补偿系数、不确定度版本追溯；批次不存在返回 404。
     */
    @Transactional(readOnly = true)
    public ReleaseDiagnosticsResponse diagnostics(String batchId) {
        ReleaseBatch batch = releaseBatches.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("放行批次不存在: " + batchId));
        List<ReleaseDiagnosticsItem> items = new ArrayList<>();
        for (var record : releases.findByBatchId(batchId)) {
            Measurement measurement = measurements.findById(record.measurementId())
                    .orElseThrow(() -> ApiException.conflict("MEASUREMENT_MISSING",
                            "放行记录关联的测量不存在: " + record.measurementId()));
            Certificate cert = certificates.findById(measurement.certificateId())
                    .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                            "测量关联的证书不存在: " + measurement.certificateId()));
            items.add(new ReleaseDiagnosticsItem(
                    measurement.measurementKey(),
                    cert.id(),
                    measurement.certVersion(),
                    DtoMapper.format(measurement.compensationCoeff()),
                    measurement.uncertaintyVersion(),
                    cert.singleBatchOnly(),
                    cert.boundBatchId()));
        }
        return new ReleaseDiagnosticsResponse(batch.batchId(), batch.releasedBy(), batch.releasedAt(), items);
    }
}
