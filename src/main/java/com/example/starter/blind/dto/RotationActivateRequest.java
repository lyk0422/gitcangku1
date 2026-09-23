package com.example.starter.blind.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 激活职责轮换单请求体；由实验负责人提交。rotationKey 取自路径，全局唯一。
 *
 * @param expectedExperimentVersion 提交时所见实验职责版本，必须等于激活前版本，否则 409
 * @param effectiveAt               生效 UTC 时刻（Unix 毫秒），不得晚于当前时刻
 * @param roster                    完整目标名册（三类角色，每类至少一人）
 */
public record RotationActivateRequest(
        @NotNull(message = "expectedExperimentVersion 不能为空")
        Long expectedExperimentVersion,
        @NotNull(message = "effectiveAt 不能为空")
        Long effectiveAt,
        @Valid
        @NotNull(message = "roster 不能为空")
        RotationRosterRequest roster
) {
}
