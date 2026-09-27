package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量封箱请求：一次提交 1～200 个标签封箱；任一标签校验失败整次回滚，不留下部分占用。
 */
public record SealBoxesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "seals 不能为空")
        @Size(min = 1, max = 200, message = "seals 必须包含 1～200 个封箱条目")
        List<@Valid SealItem> seals
) {
    /**
     * 单个封箱条目：labelNo 标签号（必须落在批次号段内），quantity 本次封箱数量（正整数）。
     */
    public record SealItem(
            @NotNull(message = "labelNo 不能为空")
            @Positive(message = "labelNo 必须为正整数") Long labelNo,
            @NotNull(message = "quantity 不能为空")
            @Positive(message = "quantity 必须为正整数") Integer quantity
    ) {
    }
}
