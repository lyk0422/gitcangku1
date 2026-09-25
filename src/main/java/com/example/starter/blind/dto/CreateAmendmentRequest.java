package com.example.starter.blind.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 创建盲法协议修订请求体。
 *
 * @param ratioA      区组内处理 A 的比例（正整数）
 * @param ratioB      区组内处理 B 的比例（正整数），与 ratioA 之和必须为 100
 * @param effectiveAt 生效 UTC 时刻（Unix 毫秒），不得早于提交时刻
 */
public record CreateAmendmentRequest(
        @NotNull(message = "ratioA 不能为空")
        @Min(value = 1, message = "比例必须为正整数")
        Integer ratioA,

        @NotNull(message = "ratioB 不能为空")
        @Min(value = 1, message = "比例必须为正整数")
        Integer ratioB,

        @NotNull(message = "effectiveAt 不能为空")
        Long effectiveAt
) {
}
