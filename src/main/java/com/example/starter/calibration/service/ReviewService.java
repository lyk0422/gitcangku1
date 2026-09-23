package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.ItemsConflictException;
import com.example.starter.calibration.api.dto.RejectionRequest;
import com.example.starter.calibration.api.dto.ReviewRequest;
import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.ReleaseBatch;
import com.example.starter.calibration.model.ReleaseRecord;
import com.example.starter.calibration.model.ReviewItem;
import com.example.starter.calibration.model.BatchReview;
import com.example.starter.calibration.repo.BatchRepository;
import com.example.starter.calibration.repo.IdempotencyRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.ReviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 放行后复核服务：复核员以 reviewKey 对一个 RELEASED 批次提交 1～全部测量的驳回集合，
 * 每项附版本与原因；成功后原子冻结整批版本快照、批次置 REVIEW_REQUIRED、被驳回测量置 REJECTED。
 *
 * <p>幂等：reviewKey 同参（含换序）重放返回首次结果，异参 409，业务失败回滚不占键；
 * reviewKey 全局唯一。复核人不得为原放行人（403）。
 */
@Service
public class ReviewService {

    /** 驳回原因/说明最大长度。 */
    static final int MAX_REASON_LENGTH = 500;

    private final BatchRepository batches;
    private final ReleaseRepository releases;
    private final MeasurementRepository measurements;
    private final ReviewRepository reviews;
    private final IdempotencyRepository idempotency;
    private final ObjectMapper objectMapper;

    public ReviewService(BatchRepository batches,
                         ReleaseRepository releases,
                         MeasurementRepository measurements,
                         ReviewRepository reviews,
                         IdempotencyRepository idempotency,
                         ObjectMapper objectMapper) {
        this.batches = batches;
        this.releases = releases;
        this.measurements = measurements;
        this.reviews = reviews;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交复核驳回。整批原子生效，任一驳回项位置缺失或版本变化则整批失败（409）并回滚。
     */
    @Transactional
    public ReviewResponse review(String batchId, ReviewRequest request, String actor) {
        String reviewer = Inputs.requireText(actor, "X-Actor-Id");
        String batch = Inputs.requireText(batchId, "batchId");
        String reviewKey = Inputs.requireBoundedText(request.reviewKey(), "reviewKey", 64);
        List<RejectionRequest> rejections = validateShape(request);

        String fingerprint = fingerprint(batch, reviewer, rejections);
        var existing = idempotency.find(reviewKey);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        ReleaseBatch lockedBatch = batches.findByIdForUpdate(batch)
                .orElseThrow(() -> ApiException.notFound("放行批次不存在: " + batch));
        if (lockedBatch.status() != BatchStatus.RELEASED) {
            // 并发复核可能已在等待行锁期间提交：持锁后复查幂等表，同参重放、异参冲突
            var afterLock = idempotency.find(reviewKey);
            if (afterLock.isPresent()) {
                return replay(afterLock.get(), fingerprint);
            }
            throw ApiException.conflict("BATCH_NOT_RELEASABLE",
                    "批次当前状态不允许复核: " + lockedBatch.status());
        }
        if (lockedBatch.releasedBy().equals(reviewer)) {
            throw ApiException.forbidden("REVIEWER_IS_RELEASER", "复核人不能是该批原放行人");
        }

        List<ReleaseRecord> positions = releases.findByBatchId(batch);
        int size = positions.size();

        // 归一化驳回项：按位置去重在入参校验已保证；此处映射 position -> 请求
        Map<Integer, RejectionRequest> byPosition = new LinkedHashMap<>();
        for (RejectionRequest rejection : rejections.stream()
                .sorted(Comparator.comparingInt(RejectionRequest::position)).toList()) {
            byPosition.put(rejection.position(), rejection);
        }

        List<ItemFailure> failures = new ArrayList<>();
        // 位置 -> 持锁测量，供冻结快照与状态更新
        Map<Integer, Measurement> lockedMeasurements = new LinkedHashMap<>();
        List<Integer> rejectedPositions = new ArrayList<>(byPosition.keySet());
        for (int position : rejectedPositions) {
            if (position < 1 || position > size) {
                failures.add(new ItemFailure("position:" + position, List.of("POSITION_NOT_FOUND")));
                continue;
            }
            long measurementId = positions.get(position - 1).measurementId();
            Measurement measurement = measurements.findByIdForUpdate(measurementId)
                    .orElseThrow(() -> ApiException.conflict("MEASUREMENT_MISSING",
                            "批次位置对应测量不存在: " + measurementId));
            RejectionRequest rejection = byPosition.get(position);
            if (measurement.version() != rejection.version()) {
                failures.add(new ItemFailure(measurement.measurementKey(), List.of("VERSION_MISMATCH")));
            } else {
                lockedMeasurements.put(position, measurement);
            }
        }

        if (!failures.isEmpty()) {
            // 整批失败：抛出后事务回滚，幂等键不占用
            throw new ItemsConflictException("REVIEW_FAILED",
                    "复核驳回失败：存在位置越界或版本变化的测量", failures);
        }

        // 冻结整批版本快照（全部位置：键、版本、驳回标记）
        List<SnapshotEntry> snapshotEntries = new ArrayList<>();
        for (ReleaseRecord record : positions) {
            Measurement measurement = lockedMeasurements.containsKey(record.position())
                    ? lockedMeasurements.get(record.position())
                    : measurements.findById(record.measurementId()).orElseThrow();
            snapshotEntries.add(new SnapshotEntry(
                    record.position(), measurement.measurementKey(), measurement.version(),
                    lockedMeasurements.containsKey(record.position())));
        }
        String snapshot = writeJson(snapshotEntries);

        Instant reviewedAt = Instant.now();
        long reviewId = reviews.insertReview(new BatchReview(
                0L, batch, reviewKey, reviewer, snapshot, reviewedAt));
        for (int position : rejectedPositions) {
            Measurement measurement = lockedMeasurements.get(position);
            reviews.insertItem(new ReviewItem(0L, reviewId, position,
                    measurement.id(), measurement.version(),
                    byPosition.get(position).reason().trim()));
            measurements.markRejected(measurement.id());
        }
        batches.markReviewRequired(batch, reviewedAt);

        ReviewResponse response = new ReviewResponse(
                batch, reviewKey, reviewer, BatchStatus.REVIEW_REQUIRED.name(),
                reviewedAt, List.copyOf(rejectedPositions));
        idempotency.insert(reviewKey, IdempotencyRepository.KIND_REVIEW,
                fingerprint, writeJson(response));
        return response;
    }

    private List<RejectionRequest> validateShape(ReviewRequest request) {
        if (request == null || request.rejections() == null || request.rejections().isEmpty()) {
            throw ApiException.badRequest("驳回集合必须包含 1～全部测量");
        }
        List<RejectionRequest> normalized = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (RejectionRequest rejection : request.rejections()) {
            if (rejection == null || rejection.position() == null || rejection.version() == null) {
                throw ApiException.badRequest("驳回项必须包含 position 与 version");
            }
            int position = rejection.position();
            int version = rejection.version();
            if (position < 1 || version < 1) {
                throw ApiException.badRequest("position 与 version 必须为正整数");
            }
            String reason = Inputs.optionalNote(rejection.reason(), "reason", MAX_REASON_LENGTH);
            if (reason == null) {
                throw ApiException.badRequest("每项驳回原因不能为空");
            }
            if (!seen.add(position)) {
                throw ApiException.badRequest("驳回集合包含重复位置: " + position);
            }
            normalized.add(new RejectionRequest(position, version, reason));
        }
        return normalized;
    }

    /**
     * 归一化参数指纹：批次、复核人固定，驳回项按位置排序后逐项拼接，换序不影响指纹。
     */
    private String fingerprint(String batch, String reviewer, List<RejectionRequest> rejections) {
        StringBuilder sb = new StringBuilder("REVIEW|").append(batch).append('|').append(reviewer);
        rejections.stream()
                .sorted(Comparator.comparingInt(RejectionRequest::position))
                .forEach(r -> sb.append('|').append(r.position())
                        .append(':').append(r.version()).append(':').append(r.reason()));
        return sb.toString();
    }

    private ReviewResponse replay(IdempotencyRepository.StoredRequest stored, String fingerprint) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "幂等键 " + stored.requestId() + " 已用于不同参数的请求");
        }
        try {
            return objectMapper.readValue(stored.responseJson(), ReviewResponse.class);
        } catch (Exception ex) {
            throw ApiException.conflict("IDEMPOTENCY_CORRUPT", "幂等响应快照无法解析");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化复核快照失败", ex);
        }
    }

    /**
     * 冻结快照中的单条目。
     */
    record SnapshotEntry(int position, String measurementKey, int version, boolean rejected) {
    }
}
