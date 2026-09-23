package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.ReReleaseRequest;
import com.example.starter.calibration.api.dto.ReReleaseResponse;
import com.example.starter.calibration.model.BatchSnapshotItem;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseBatch;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.LineageRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.ReviewRepository;

/**
 * 重新放行服务：针对 REVIEW_REQUIRED 批次提交全部位置的精确映射（驳回项用最新修订，
 * 未驳回项用原测量，并附所有版本）。任一缺漏、重复、证书失效、值越界或版本变化整批失败；
 * 成功生成新批次并建立逐项血缘，旧批次与旧结果保持不可变。
 * requestId 同参（映射换序）重放返回原结果，异参 409；失败不占键。
 */
@Service
public class ReReleaseService {

    private final BatchRepository batches;
    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final ReviewRepository reviews;
    private final LineageRepository lineage;

    public ReReleaseService(BatchRepository batches,
                            MeasurementRepository measurements,
                            CertificateRepository certificates,
                            ReleaseRepository releases,
                            ReviewRepository reviews,
                            LineageRepository lineage) {
        this.batches = batches;
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.reviews = reviews;
        this.lineage = lineage;
    }

    /**
     * 重新放行。整批原子生效：任一映射项不满足条件则整批拒绝（409）并返回各项原因。
     */
    @Transactional
    public ReReleaseResponse reRelease(String batchId, ReReleaseRequest request, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        String sourceBatchId = Inputs.requireText(batchId, "batchId");
        String requestId = Inputs.requireText(request.requestId(), "requestId");
        List<ReReleaseRequest.ReReleaseItemRequest> items = request.items();
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("重新放行必须提交原批次全部位置的精确映射");
        }
        List<String> canonicalItems = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (ReReleaseRequest.ReReleaseItemRequest item : items) {
            String key = Inputs.requireText(item.measurementKey(), "items[].measurementKey");
            if (item.version() == null || item.version() < 0) {
                throw ApiException.badRequest("items[].version 必须为不小于 0 的整数");
            }
            if (!seenKeys.add(key)) {
                throw ApiException.badRequest("重新放行映射包含重复测量键: " + key);
            }
            canonicalItems.add(key + (char) 1 + item.version().toString());
        }
        String fingerprint = Fingerprints.of(sourceBatchId, canonicalItems);

        // 幂等重放：同键同参（映射换序）返回原结果，同键异参 409
        var existing = batches.findByRequestId(requestId);
        if (existing.isPresent()) {
            ReleaseBatch batch = existing.get();
            if (fingerprint.equals(batch.requestFingerprint())) {
                return toResponse(batch);
            }
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "requestId 已用于不同参数: " + requestId);
        }

        ReleaseBatch source = batches.findByIdForUpdate(sourceBatchId)
                .orElseThrow(() -> ApiException.notFound("放行批次不存在: " + sourceBatchId));
        // 持有批次行锁后复查幂等键：并发同键请求按提交顺序重放
        var raced = batches.findByRequestId(requestId);
        if (raced.isPresent()) {
            ReleaseBatch batch = raced.get();
            if (fingerprint.equals(batch.requestFingerprint())) {
                return toResponse(batch);
            }
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "requestId 已用于不同参数: " + requestId);
        }
        if (source.status() != BatchStatus.REVIEW_REQUIRED) {
            throw ApiException.conflict("BATCH_NOT_UNDER_REVIEW",
                    "仅复核驳回状态的批次可重新放行: " + sourceBatchId + " 状态 " + source.status());
        }

        List<BatchSnapshotItem> snapshot = reviews.findSnapshot(sourceBatchId);
        Map<String, ReReleaseRequest.ReReleaseItemRequest> submitted = new HashMap<>();
        for (ReReleaseRequest.ReReleaseItemRequest item : items) {
            submitted.put(item.measurementKey().trim(), item);
        }

        // 逐位置解析映射：驳回项须为链头最新修订，未驳回项须为冻结版本原测量
        List<ItemFailure> failures = new ArrayList<>();
        List<ResolvedItem> resolved = new ArrayList<>();
        for (BatchSnapshotItem position : snapshot) {
            Measurement sourceMeasurement = measurements.findById(position.measurementId()).orElseThrow();
            String key = sourceMeasurement.measurementKey();
            ReReleaseRequest.ReReleaseItemRequest mapping = submitted.remove(key);
            if (mapping == null) {
                failures.add(new ItemFailure(key, List.of("MAPPING_MISSING")));
                continue;
            }
            if (!position.rejected()) {
                if (mapping.version() != position.measurementVersion()) {
                    failures.add(new ItemFailure(key, List.of("VERSION_CONFLICT")));
                    continue;
                }
                resolved.add(new ResolvedItem(sourceMeasurement, position.measurementId()));
                continue;
            }
            Measurement head = measurements.findByKey(key).orElseThrow();
            if (head.version() == sourceMeasurement.version()) {
                failures.add(new ItemFailure(key, List.of("REVISION_MISSING")));
                continue;
            }
            if (mapping.version() != head.version()) {
                failures.add(new ItemFailure(key, List.of("VERSION_CONFLICT")));
                continue;
            }
            resolved.add(new ResolvedItem(head, position.measurementId()));
        }
        for (String extra : submitted.keySet()) {
            failures.add(new ItemFailure(extra, List.of("MAPPING_UNEXPECTED")));
        }

        // 按测量键字典序加行锁并校验状态、证书与值域，与复核/撤销/修订按提交顺序互斥
        if (failures.isEmpty()) {
            for (ResolvedItem item : resolved.stream()
                    .sorted((a, b) -> a.measurement().measurementKey()
                            .compareTo(b.measurement().measurementKey()))
                    .toList()) {
                Measurement locked = measurements.findByIdForUpdate(item.measurement().id()).orElseThrow();
                List<String> reasons = new ArrayList<>();
                if (locked.status() == MeasurementStatus.PENDING) {
                    // 修订项：待重新放行
                } else if (locked.status() == MeasurementStatus.RELEASED
                        && locked.id() == item.sourceMeasurementId()) {
                    // 未驳回项：保持已放行
                } else {
                    reasons.add("NOT_RELEASABLE");
                }
                Certificate cert = certificates.findByIdForUpdate(locked.certificateId())
                        .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                                "测量关联的证书不存在: " + locked.certificateId()));
                if (cert.revoked()) {
                    reasons.add("CERTIFICATE_REVOKED");
                }
                if (!locked.passed()) {
                    reasons.add("NOT_PASSED");
                }
                if (!reasons.isEmpty()) {
                    failures.add(new ItemFailure(locked.measurementKey(), reasons));
                }
            }
        }
        if (!failures.isEmpty()) {
            throw new BatchRejectedException("RE_RELEASE_REJECTED",
                    "重新放行被拒绝：映射不完整或存在不满足条件的测量", failures);
        }

        String newBatchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        try {
            batches.insert(new ReleaseBatch(newBatchId, releaser, releasedAt, BatchStatus.RELEASED,
                    requestId, fingerprint, sourceBatchId));
        } catch (DuplicateKeyException ex) {
            // 并发同键：以先提交者为准
            ReleaseBatch batch = batches.findByRequestId(requestId).orElseThrow();
            if (fingerprint.equals(batch.requestFingerprint())) {
                return toResponse(batch);
            }
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "requestId 已用于不同参数: " + requestId);
        }
        batches.updateStatus(sourceBatchId, BatchStatus.SUPERSEDED);
        for (ResolvedItem item : resolved) {
            Measurement m = item.measurement();
            if (m.status() == MeasurementStatus.PENDING) {
                measurements.markReleased(m.id());
            }
            releases.insert(newBatchId, m.id(), releaser, releasedAt);
            lineage.insert(newBatchId, m.id(), sourceBatchId, item.sourceMeasurementId());
        }
        return new ReReleaseResponse(newBatchId, sourceBatchId, releaser, releasedAt,
                toResponseItems(newBatchId));
    }

    /**
     * 由批次记录重建响应（幂等重放）。
     */
    private ReReleaseResponse toResponse(ReleaseBatch batch) {
        return new ReReleaseResponse(batch.batchId(), batch.sourceBatchId(), batch.releasedBy(),
                batch.releasedAt(), toResponseItems(batch.batchId()));
    }

    private List<ReReleaseResponse.ReReleasedItem> toResponseItems(String batchId) {
        return measurements.findByBatchId(batchId).stream()
                .map(m -> new ReReleaseResponse.ReReleasedItem(m.measurementKey(), m.version()))
                .sorted((a, b) -> a.measurementKey().compareTo(b.measurementKey()))
                .toList();
    }

    /**
     * 解析后的映射项：新批次使用的测量记录及其在来源批次中的位置。
     *
     * @param measurement         新批次使用的测量记录
     * @param sourceMeasurementId 来源批次中对应位置的测量记录 ID
     */
    private record ResolvedItem(Measurement measurement, long sourceMeasurementId) {
    }
}
