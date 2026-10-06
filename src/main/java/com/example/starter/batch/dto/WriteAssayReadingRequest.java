package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 写入平行测定读数请求。读数写入该检验项当前进行中（OPEN）的轮次；
 * expectedVersion 为该轮当前已写入读数条数（0～2），不符返回 409。
 */
public record WriteAssayReadingRequest(
        @NotBlank(message = "requestId 不能为空") String requestId,
        @NotBlank(message = "testItem 不能为空") String testItem,
        @NotNull(message = "seq 不能为空")
        @Min(value = 1, message = "seq 必须为 1～3")
        @Max(value = 3, message = "seq 必须为 1～3") Integer seq,
        @NotBlank(message = "value 不能为空")
        @Pattern(regexp = "^-?\\d{1,18}(\\.\\d{1,4})?$", message = "value 必须为最多 4 位小数的十进制字符串")
        String value,
        @NotNull(message = "expectedVersion 不能为空")
        @Min(value = 0, message = "expectedVersion 不能为负数") Integer expectedVersion
) {
}
