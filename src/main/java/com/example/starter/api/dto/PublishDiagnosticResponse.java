package com.example.starter.api.dto;

import com.example.starter.domain.PolicyViolation;

import java.util.List;

/**
 * 发布阻断诊断：按当前策略版本评估锁定图，violations 为空表示可发布。
 *
 * @param currentPolicyVersion 当前策略版本；未定义策略时为 null（不阻断）
 */
public record PublishDiagnosticResponse(
        long lockFileId,
        boolean published,
        Integer currentPolicyVersion,
        List<PolicyViolation> violations) {
}
