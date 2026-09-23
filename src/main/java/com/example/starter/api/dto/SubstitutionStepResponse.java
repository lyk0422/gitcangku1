package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件中冻结的单个替代步骤解释。
 */
public record SubstitutionStepResponse(
        int stepOrder,
        String originalCoordinate,
        String sourcePattern,
        String platform,
        String finalCoordinate,
        long policyVersion,
        List<CandidateRejectionResponse> rejections) {
}
