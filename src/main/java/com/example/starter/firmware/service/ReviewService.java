package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ConfirmReviewRequest;
import com.example.starter.firmware.api.RejectReviewRequest;
import com.example.starter.firmware.api.ReviewView;
import com.example.starter.firmware.domain.FirmwareCertChain;
import com.example.starter.firmware.domain.ReceiptReview;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReviewStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.CertChainRepository;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.ReceiptReviewRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 撤销链回执的人工复核：确认后才完成设备版本更新；拒绝记录原因且永不更新。
 * 复核单进入终态（CONFIRMED/REJECTED）后不可更改；裁决与设备版本更新在同一事务提交。
 */
@Service
public class ReviewService {

    private final ReceiptReviewRepository reviewRepository;
    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final CertChainRepository certChainRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ReviewService(ReceiptReviewRepository reviewRepository, TaskRepository taskRepository,
                         ReleaseRepository releaseRepository, DeviceRepository deviceRepository,
                         CertChainRepository certChainRepository,
                         IdempotencyService idempotency, Clock clock) {
        this.reviewRepository = reviewRepository;
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.certChainRepository = certChainRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public ReviewView get(long taskId) {
        ReceiptReview review = requireReview(taskId);
        return ReviewView.of(review, chainVersionOf(review.chainId()));
    }

    /**
     * 人工确认：仅 PENDING_REVIEW 可确认；确认时把设备版本更新为任务目标版本。
     */
    public ReviewView confirm(long taskId, ConfirmReviewRequest request) {
        String fingerprint = String.join("|", "review.confirm", String.valueOf(taskId), request.operator());
        return idempotency.execute(request.requestId(), "review.confirm", fingerprint, () -> {
            ReceiptReview review = requireReviewForUpdate(taskId);
            if (review.status() == ReviewStatus.CONFIRMED) {
                return toView(review);
            }
            if (review.status() == ReviewStatus.REJECTED) {
                throw ApiException.conflict("REVIEW_ALREADY_DECIDED", "复核单已拒绝，不能确认");
            }
            int affected = reviewRepository.decide(taskId, ReviewStatus.CONFIRMED, null,
                    request.operator(), Instant.now(clock));
            if (affected != 1) {
                throw ApiException.conflict("REVIEW_ALREADY_DECIDED", "复核单已被并发裁决");
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("复核任务缺失: " + taskId));
            ReleaseOrder order = releaseRepository.findById(task.releaseId())
                    .orElseThrow(() -> new IllegalStateException("复核任务所属发布单缺失"));
            deviceRepository.updateCurrentVersion(task.deviceId(), order.toVersion());
            return toView(reviewRepository.findByTask(taskId).orElseThrow());
        }, ReviewView.class);
    }

    /**
     * 人工拒绝：仅 PENDING_REVIEW 可拒绝；拒绝后设备版本停留来源版本，不再自动更新。
     */
    public ReviewView reject(long taskId, RejectReviewRequest request) {
        String fingerprint = String.join("|", "review.reject", String.valueOf(taskId),
                request.reason(), request.operator());
        return idempotency.execute(request.requestId(), "review.reject", fingerprint, () -> {
            ReceiptReview review = requireReviewForUpdate(taskId);
            if (review.status() == ReviewStatus.REJECTED) {
                return toView(review);
            }
            if (review.status() == ReviewStatus.CONFIRMED) {
                throw ApiException.conflict("REVIEW_ALREADY_DECIDED", "复核单已确认，不能拒绝");
            }
            int affected = reviewRepository.decide(taskId, ReviewStatus.REJECTED, request.reason(),
                    request.operator(), Instant.now(clock));
            if (affected != 1) {
                throw ApiException.conflict("REVIEW_ALREADY_DECIDED", "复核单已被并发裁决");
            }
            return toView(reviewRepository.findByTask(taskId).orElseThrow());
        }, ReviewView.class);
    }

    private ReceiptReview requireReview(long taskId) {
        return reviewRepository.findByTask(taskId)
                .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND",
                        "任务不存在撤销链回执复核单: " + taskId));
    }

    private ReceiptReview requireReviewForUpdate(long taskId) {
        return reviewRepository.findByTaskForUpdate(taskId)
                .orElseThrow(() -> ApiException.notFound("REVIEW_NOT_FOUND",
                        "任务不存在撤销链回执复核单: " + taskId));
    }

    private Integer chainVersionOf(long chainId) {
        return certChainRepository.findById(chainId).map(FirmwareCertChain::chainVersion).orElse(null);
    }

    private ReviewView toView(ReceiptReview review) {
        return ReviewView.of(review, chainVersionOf(review.chainId()));
    }
}
