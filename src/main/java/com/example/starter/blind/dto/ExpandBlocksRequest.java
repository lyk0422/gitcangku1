package com.example.starter.blind.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 区组扩容请求体。
 *
 * @param additionalBlocks 追加的区组数量，1~6；与现有区组数合计不得超过 8
 */
public record ExpandBlocksRequest(
        @NotNull(message = "additionalBlocks 不能为空")
        @Min(value = 1, message = "additionalBlocks 必须在 1~6 之间")
        @Max(value = 6, message = "additionalBlocks 必须在 1~6 之间")
        Integer additionalBlocks
) {
}
