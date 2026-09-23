package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.BatchDiffResponse;
import com.example.starter.calibration.api.dto.ReviewRequest;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.model.BatchSnapshotItem;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.ReleaseBatch;
import com.example.starter.calibration.model.ReviewRecord;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReviewRepository;

/**
 * 复核服务：复核员对一个生效中的放行批次提交 1～全部测量的驳回集合（原子生效），
 * 并提供批次差异只读查询。复核人不能是原放行人；reviewKey 全局唯一作为幂等键。
 */
@Service
public class ReviewService {

    private final BatchRepository batches;
    private final MeasurementRepository measurements;
    private final ReviewRepository reviews;

    public ReviewService(BatchRepository batches,
                         MeasurementRepository measurements,
                         ReviewRepository reviews) {
        this.batches = batches;
        this.measurements = measurements;
        this.reviews = reviews;
    }

    /**
     * 提交复核驳回。成功后原子地把批次置 REVIEW_REQUIRED、被驳回测量置 REJECTED，
     * 未驳回项保持内容但不再对外可用，并冻结整批版本快照。
     * reviewKey 同参（映射换序）重放返回原结果，异参返回 409；失败不占键。
     */
    @Transactional
    public ReviewResponse review(ReviewRequest request, String actor) {
        String reviewer = Inputs.requireText(actor, "X-Actor-Id");
        String reviewKey = Inputs.requireText(request.reviewKey(), "reviewKey");
        String batchId = Inputs.requireText(request.batchId(), "batchId");
        List<ReviewRequest.ReviewItemRequest> items = request.items();
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("复核驳回集合必须为 1～全部测量");
        }
        List<String> canonicalItems = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (ReviewRequest.ReviewItemRequest item : items) {
            String key = Inputs.requireText(item.measurementKey(), "items[].measurementKey");
            if (item.version() == null || item.version() < 0) {
                throw ApiException.badRequest("items[].version 必须为不小于 0 的整数");
            }
            String reason = Inputs.requireText(item.reason(), "items[].reason");
            if (!seenKeys.add(key)) {
                throw ApiException.badRequest("复核驳回集合包含重复测量键: " + key);
            }
            canonicalItems.add(key + (char) 1 + item.version() + (char) 1 + reason);
        }
        String fingerprint = Fingerprints.of(batchId, canonicalItems);

        // 幂等重放：同键同参（映射换序）返回原结果，同键异参 409
        var existing = reviews.findByReviewKey(reviewKey);
        if (existing.isPresent()) {
            ReviewRecord record = existing.get();
            if (record.requestFingerprint().equals(fingerprint)) {
                return toResponse(record);
            }
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "reviewKey 已用于不同参数: " + reviewKey);
        }

        ReleaseBatch batch = batches.findByIdForUpdate(batchId)
                .orElseThrow(() -> ApiException.notFound("放行批次不存在: " + batchId));
        // 持有批次行锁后复查幂等键：并发同键请求按提交顺序重放
        var raced = reviews.findByReviewKey(reviewKey);
        if (raced.isPresent()) {
            ReviewRecord record = raced.get();
            if (record.requestFingerprint().equals(fingerprint)) {
                return toResponse(record);
            }
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "reviewKey 已用于不同参数: " + reviewKey);
        }
        if (batch.status() != BatchStatus.RELEASED) {
            throw ApiException.conflict("BATCH_NOT_ACTIVE",
                    "批次不在可复核状态: " + batchId + " 状态 " + batch.status());
        }
        if (batch.releasedBy().equals(reviewer)) {
            throw ApiException.conflict("SAME_ACTOR", "复核人不能是原放行人");
        }

        List<Measurement> batchItems = measurements.findByBatchId(batchId);
        Map<String, Measurement> byKey = new HashMap<>();
        for (Measurement m : batchItems) {
            byKey.put(m.measurementKey(), m);
        }

        // 按测量键字典序加行锁并校验版本，避免并发复核/修订间死锁
        List<ItemFailure> failures = new ArrayList<>();
        Map<Long, String> reasonById = new HashMap<>();
        for (ReviewRequest.ReviewItemRequest item : items.stream()
                .sorted((a, b) -> a.measurementKey().compareTo(b.measurementKey())).toList()) {
            String key = item.measurementKey().trim();
            Measurement member = byKey.get(key);
            if (member == null) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_IN_BATCH")));
                continue;
            }
            Measurement locked = measurements.findByIdForUpdate(member.id()).orElseThrow();
            if (locked.version() != item.version()) {
                failures.add(new ItemFailure(key, List.of("VERSION_CONFLICT")));
                continue;
            }
            reasonById.put(locked.id(), item.reason().trim());
        }
        if (!failures.isEmpty()) {
            throw new BatchRejectedException("REVIEW_REJECTED", "复核驳回被拒绝：存在不满足条件的驳回项", failures);
        }

        Instant reviewedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        long reviewId;
        try {
            reviewId = reviews.insertReview(reviewKey, batchId, reviewer, fingerprint, reviewedAt);
        } catch (DuplicateKeyException ex) {
            // 并发同键：以先提交者为准
            ReviewRecord record = reviews.findByReviewKey(reviewKey).orElseThrow();
            if (record.requestFingerprint().equals(fingerprint)) {
                return toResponse(record);
            }
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "reviewKey 已用于不同参数: " + reviewKey);
        }

        // 冻结整批版本快照；被驳回测量置 REJECTED；批次置 REVIEW_REQUIRED
        for (Measurement m : batchItems) {
            boolean rejected = reasonById.containsKey(m.id());
            reviews.insertSnapshot(batchId, reviewId, m.id(), m.version(), rejected,
                    rejected ? reasonById.get(m.id()) : null);
            if (rejected) {
                measurements.markRejected(m.id());
            }
        }
        batches.updateStatus(batchId, BatchStatus.REVIEW_REQUIRED);

        return new ReviewResponse(reviewKey, batchId, reviewer, reviewedAt,
                toRejectedItems(reviews.findSnapshot(batchId)));
    }

    /**
     * 批次差异只读查询：批次状态、复核信息及每个位置的驳回差异；批次不存在返回 404。
     */
    @Transactional(readOnly = true)
    public BatchDiffResponse diff(String batchId) {
        ReleaseBatch batch = batches.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("放行批次不存在: " + batchId));
        ReviewRecord review = reviews.findByBatchId(batchId).orElse(null);
        String successor = batches.findSuccessorBatchId(batchId).orElse(null);
        List<BatchDiffResponse.BatchDiffItem> items = new ArrayList<>();
        List<BatchSnapshotItem> snapshot = reviews.findSnapshot(batchId);
        if (!snapshot.isEmpty()) {
            for (BatchSnapshotItem item : snapshot) {
                Measurement m = measurements.findById(item.measurementId()).orElseThrow();
                items.add(new BatchDiffResponse.BatchDiffItem(
                        m.measurementKey(), item.measurementVersion(), item.rejected(), item.reason()));
            }
        } else {
            for (Measurement m : measurements.findByBatchId(batchId)) {
                items.add(new BatchDiffResponse.BatchDiffItem(
                        m.measurementKey(), m.version(), false, null));
            }
        }
        return new BatchDiffResponse(batchId, batch.status().name(), batch.releasedBy(), batch.releasedAt(),
                batch.sourceBatchId(), successor,
                review == null ? null : review.reviewKey(),
                review == null ? null : review.reviewer(),
                review == null ? null : review.reviewedAt(),
                items);
    }

    private ReviewResponse toResponse(ReviewRecord record) {
        return new ReviewResponse(record.reviewKey(), record.batchId(), record.reviewer(),
                record.reviewedAt(), toRejectedItems(reviews.findSnapshot(record.batchId())));
    }

    private List<ReviewResponse.RejectedItem> toRejectedItems(List<BatchSnapshotItem> snapshot) {
        List<ReviewResponse.RejectedItem> rejected = new ArrayList<>();
        for (BatchSnapshotItem item : snapshot) {
            if (item.rejected()) {
                Measurement m = measurements.findById(item.measurementId()).orElseThrow();
                rejected.add(new ReviewResponse.RejectedItem(
                        m.measurementKey(), item.measurementVersion(), item.reason()));
            }
        }
        return rejected;
    }
}
