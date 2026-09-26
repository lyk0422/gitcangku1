package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量封箱请求：一次提交 1～100 条封箱记录；任一标签校验失败整次回滚，不留下部分占用。
 */
public record SealBoxesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "seals 不能为空")
        @Size(min = 1, max = 100, message = "seals 必须包含 1～100 条封箱记录")
        List<@Valid SealSpec> seals
) {
    /**
     * 单条封箱记录：一个标签号对应一个封箱，封箱数量为正整数。
     */
    public record SealSpec(
            @NotBlank(message = "sealKey 不能为空") String sealKey,
            @NotNull(message = "labelNo 不能为空") Long labelNo,
            @NotNull(message = "quantity 不能为空")
            @Positive(message = "quantity 必须为正整数") Integer quantity
    ) {
    }
}
