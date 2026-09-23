package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.ItemsConflictException;
import com.example.starter.calibration.api.dto.ReReleaseItem;
import com.example.starter.calibration.api.dto.ReReleaseRequest;
import com.example.starter.calibration.api.dto.ReReleaseResponse;
import com.example.starter.calibration.model.BatchLineage;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseBatch;
import com.example.starter.calibration.model.ReleaseRecord;
import com.example.starter.calibration.model.ReviewItem;
import com.example.starter.calibration.model.BatchReview;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.example.starter.calibration.repo.LineageRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.ReviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 重新放行服务：对 REVIEW_REQUIRED 批次提交全部位置的精确映射（驳回项用最新修订、
 * 未驳回项用原测量，逐项附版本）。任一缺漏、重复、证书失效、值越界或版本变化整批失败；
 * 成功生成新批次并建立逐项血缘，旧批次与旧结果保持不可变。
 *
 * <p>幂等：requestId 同参（含映射换序）重放返回首次结果，异参 409，业务失败回滚不占键。
 */
@Service
public class ReReleaseService {

    private final BatchRepository batches;
    private final ReleaseRepository releases;
    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReviewRepository reviews;
    private final LineageRepository lineages;
    private final IdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;

    public ReReleaseService(BatchRepository batches,
                            ReleaseRepository releases,
                            MeasurementRepository measurements,
                            CertificateRepository certificates,
                            ReviewRepository reviews,
                            LineageRepository lineages,
                            IdempotencyRepository idempotency,
                            ObjectMapper objectMapper) {
        this.batches = batches;
        this.releases = releases;
        this.measurements = measurements;
        this.certificates = certificates;
        this.reviews = reviews;
        this.lineages = lineages;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * 重新放行。整批原子生效；映射缺漏/重复/位置越界、证书失效、值越界、版本变化均整批失败（409）。
     */
    @Transactional
    public ReReleaseResponse reRelease(String batchId, ReReleaseRequest request, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        String batch = Inputs.requireText(batchId, "batchId");
        String requestId = Inputs.requireBoundedText(request.requestId(), "requestId", 64);
        List<ReReleaseItem> items = validateShape(request);

        String fingerprint = fingerprint(batch, releaser, items);
        var existing = idempotency.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        ReleaseBatch sourceBatch = batches.findByIdForUpdate(batch)
                .orElseThrow(() -> ApiException.notFound("放行批次不存在: " + batch));
        if (sourceBatch.status() != BatchStatus.REVIEW_REQUIRED) {
            var afterLock = idempotency.find(requestId);
            if (afterLock.isPresent()) {
                return replay(afterLock.get(), fingerprint);
            }
            throw ApiException.conflict("BATCH_NOT_REVIEW_REQUIRED",
                    "批次当前状态不允许重新放行: " + sourceBatch.status());
        }
        if (lineages.existsBySourceBatchId(batch)) {
            var afterLock = idempotency.find(requestId);
            if (afterLock.isPresent()) {
                return replay(afterLock.get(), fingerprint);
            }
            throw ApiException.conflict("BATCH_ALREADY_RERELEASED",
                    "批次已被重新放行，不可重复生成后继批次: " + batch);
        }

        List<ReleaseRecord> positions = releases.findByBatchId(batch);
        BatchReview review = reviews.findByBatchId(batch)
                .orElseThrow(() -> ApiException.conflict("REVIEW_MISSING",
                        "批次缺少复核记录: " + batch));
        List<ReviewItem> rejectedItems = reviews.findItems(review.id());
        Set<Integer> rejectedPositions = new HashSet<>();
        for (ReviewItem item : rejectedItems) {
            rejectedPositions.add(item.position());
        }

        // 位置 -> 请求映射
        Map<Integer, ReReleaseItem> byPosition = new LinkedHashMap<>();
        for (ReReleaseItem item : items.stream()
                .sorted(Comparator.comparingInt(ReReleaseItem::position)).toList()) {
            byPosition.put(item.position(), item);
        }

        List<ItemFailure> failures = new ArrayList<>();
        // 位置 -> 解析后的采用测量
        Map<Integer, Measurement> resolved = new LinkedHashMap<>();
        for (int position = 1; position <= positions.size(); position++) {
            ReReleaseItem item = byPosition.get(position);
            if (item == null) {
                failures.add(new ItemFailure("position:" + position, List.of("POSITION_MISSING")));
                continue;
            }
            ReleaseRecord record = positions.get(position - 1);
            Measurement source = measurements.findByIdForUpdate(record.measurementId())
                    .orElseThrow(() -> ApiException.conflict("MEASUREMENT_MISSING",
                            "批次位置对应测量不存在: " + record.measurementId()));
            boolean rejected = rejectedPositions.contains(position);
            Measurement used;
            if (rejected) {
                // 驳回项：必须采用该测量修订链的最新后继修订
                long rootId = source.rootId() == null ? source.id() : source.rootId();
                Measurement latest = measurements.findLatestRevision(rootId).orElse(null);
                if (latest == null || !latest.measurementKey().equals(item.measurementKey())) {
                    failures.add(new ItemFailure(item.measurementKey(),
                            List.of("LATEST_REVISION_REQUIRED")));
                    continue;
                }
                used = measurements.findByKeyForUpdate(item.measurementKey()).orElseThrow();
            } else {
                // 未驳回项：必须复用原测量
                if (!source.measurementKey().equals(item.measurementKey())) {
                    failures.add(new ItemFailure(item.measurementKey(),
                            List.of("ORIGINAL_MEASUREMENT_REQUIRED")));
                    continue;
                }
                used = source;
            }
            List<String> reasons = new ArrayList<>();
            if (used.version() != item.version()) {
                reasons.add("VERSION_MISMATCH");
            }
            Certificate cert = certificates.findByIdForUpdate(used.certificateId())
                    .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                            "测量关联的证书不存在: " + used.certificateId()));
            if (cert.revoked()) {
                reasons.add("CERTIFICATE_REVOKED");
            }
            if (!used.passed()) {
                reasons.add("NOT_PASSED");
            }
            if (used.status() != MeasurementStatus.PENDING && used.status() != MeasurementStatus.RELEASED) {
                reasons.add("NOT_RELEASABLE");
            }
            if (reasons.isEmpty()) {
                resolved.put(position, used);
            } else {
                failures.add(new ItemFailure(item.measurementKey(), reasons));
            }
        }
        // 多余位置（超出原批次范围）
        for (int position : byPosition.keySet()) {
            if (position > positions.size()) {
                failures.add(new ItemFailure("position:" + position, List.of("POSITION_NOT_FOUND")));
            }
        }

        if (!failures.isEmpty()) {
            throw new ItemsConflictException("RERELEASE_FAILED",
                    "重新放行失败：存在缺漏、重复、证书失效、值越界或版本变化", failures);
        }

        String newBatchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        batches.insert(new ReleaseBatch(newBatchId, releaser, BatchStatus.RELEASED, releasedAt, null));
        List<ReReleaseResponse.ReReleaseItemResult> results = new ArrayList<>();
        for (int position = 1; position <= positions.size(); position++) {
            Measurement used = resolved.get(position);
            ReleaseRecord record = positions.get(position - 1);
            boolean revised = rejectedPositions.contains(position);
            if (used.status() != MeasurementStatus.RELEASED) {
                measurements.markReleased(used.id());
            }
            releases.insert(newBatchId, position, used.id(), releaser, releasedAt);
            lineages.insert(new BatchLineage(0L, newBatchId, batch, position,
                    record.measurementId(), used.id(), used.version(), revised));
            results.add(new ReReleaseResponse.ReReleaseItemResult(
                    position, used.measurementKey(), used.version(), revised));
        }

        ReReleaseResponse response = new ReReleaseResponse(
                newBatchId, batch, requestId, releaser, releasedAt, List.copyOf(results));
        idempotency.insert(requestId, IdempotencyRepository.KIND_RERELEASE,
                fingerprint, writeJson(response));
        return response;
    }

    private List<ReReleaseItem> validateShape(ReReleaseRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw ApiException.badRequest("重新放行必须提交原批次全部位置的映射");
        }
        List<ReReleaseItem> normalized = new ArrayList<>();
        Set<Integer> seenPositions = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        for (ReReleaseItem item : request.items()) {
            if (item == null || item.position() == null || item.version() == null) {
                throw ApiException.badRequest("映射项必须包含 position 与 version");
            }
            int position = item.position();
            int version = item.version();
            if (position < 1 || version < 1) {
                throw ApiException.badRequest("position 与 version 必须为正整数");
            }
            String key = Inputs.requireText(item.measurementKey(), "items[].measurementKey");
            if (!seenPositions.add(position)) {
                throw ApiException.badRequest("映射包含重复位置: " + position);
            }
            if (!seenKeys.add(key)) {
                throw ApiException.badRequest("映射包含重复测量键: " + key);
            }
            normalized.add(new ReReleaseItem(position, key, version));
        }
        return normalized;
    }

    /**
     * 归一化参数指纹：批次、放行人固定，映射项按位置排序后逐项拼接，换序不影响指纹。
     */
    private String fingerprint(String batch, String releaser, List<ReReleaseItem> items) {
        StringBuilder sb = new StringBuilder("RERELEASE|").append(batch).append('|').append(releaser);
        items.stream()
                .sorted(Comparator.comparingInt(ReReleaseItem::position))
                .forEach(i -> sb.append('|').append(i.position())
                        .append(':').append(i.measurementKey()).append(':').append(i.version()));
        return sb.toString();
    }

    private ReReleaseResponse replay(IdempotencyRepository.StoredRequest stored, String fingerprint) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "幂等键 " + stored.requestId() + " 已用于不同参数的请求");
        }
        try {
            return objectMapper.readValue(stored.responseJson(), ReReleaseResponse.class);
        } catch (Exception ex) {
            throw ApiException.conflict("IDEMPOTENCY_CORRUPT", "幂等响应快照无法解析");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化重新放行响应失败", ex);
        }
    }
}
