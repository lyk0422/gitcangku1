package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 写平行测定读数请求。seq 为轮次内序号 1～3 唯一；value 为十进制字符串、最多 4 位小数，
 * 原值保存不舍入；expectedVersion 为轮次当前版本（已写入读数条数），不符返回 409。
 */
public record WriteAssayReadingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotNull(message = "seq 不能为空")
        @Min(value = 1, message = "seq 必须在 1～3 之间")
        @Max(value = 3, message = "seq 必须在 1～3 之间") Integer seq,
        @NotBlank(message = "value 不能为空") String value,
        @NotBlank(message = "operator 不能为空") String operator,
        @NotNull(message = "expectedVersion 不能为空")
        @PositiveOrZero(message = "expectedVersion 不能为负") Integer expectedVersion
) {
}
