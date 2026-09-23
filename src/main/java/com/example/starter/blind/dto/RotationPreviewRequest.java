package com.example.starter.blind.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * 轮换预览请求体；只读，不写任何数据。生效时刻参与“生效前后可见范围”的说明性计算。
 *
 * @param effectiveAt 拟生效 UTC 时刻（Unix 毫秒）
 * @param roster      拟提交的完整目标名册
 */
public record RotationPreviewRequest(
        @NotNull(message = "effectiveAt 不能为空")
        Long effectiveAt,
        @Valid
        @NotNull(message = "roster 不能为空")
        RotationRosterRequest roster
) {
}
