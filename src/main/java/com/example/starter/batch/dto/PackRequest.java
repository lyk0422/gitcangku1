package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量封箱请求。items 非空，每项为一个标签的封箱（标签号 + 本箱数量，均为正整数）。
 * 一次提交先按“既有 ACTIVE + 本次提交”的完整最终集合校验，任一标签失败整次回滚，
 * 不留部分占用。
 */
public record PackRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "items 不能为空")
        @Size(min = 1, message = "items 至少包含 1 条封箱")
        List<@Valid Item> items
) {

    /**
     * 单条封箱：labelNo 标签号，quantity 本箱数量（正整数，单位：件）。
     */
    public record Item(
            @NotNull(message = "labelNo 不能为空")
            @Positive(message = "labelNo 必须为正整数") Long labelNo,
            @NotNull(message = "quantity 不能为空")
            @Positive(message = "quantity 必须为正整数") Integer quantity
    ) {
    }
}
