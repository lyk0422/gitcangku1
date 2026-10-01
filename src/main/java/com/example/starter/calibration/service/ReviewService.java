package com.example.starter.calibration.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.starter.calibration.api.dto.ReviewResponse;
import com.example.starter.calibration.api.dto.SubmitReviewRequest;
import com.example.starter.calibration.model.ReviewDecision;

/**
 * 离群复核服务：复核记录、法定人数裁决、REJECT 同事务排除读数并重算统计。
 */
@Service
public class ReviewService {

    /** 有效裁决所需的一致结论人数（要求值）。 */
    static final int REQUIRED_REVIEWS = 2;

    /**
     * 提交复核。版本已变更或证书失效时记录 STALE 复核并返回 410；
     * 两名不同复核角色结论一致时形成裁决，REJECT 在同一事务内排除读数并重算批次统计。
     */
    public ReviewResponse review(String batchKey, long outlierId, SubmitReviewRequest request) {
        throw new UnsupportedOperationException();
    }

    /**
     * 裁决评估：任一结论的有效复核数达到要求人数时形成该结论的裁决，否则保持待裁决。
     */
    static Optional<ReviewDecision> evaluateVerdict(List<ReviewDecision> validDecisions) {
        throw new UnsupportedOperationException();
    }
}