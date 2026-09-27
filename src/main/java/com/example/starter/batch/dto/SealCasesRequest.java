package com.example.starter.batch.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量封箱请求。一次提交 1～50 条封箱记录，先校验完整最终集合，
 * 任一标签失败整次回滚，不留下部分占用。
 */
public record SealCasesRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "cases 不能为空")
        @Size(min = 1, max = 50, message = "cases 必须包含 1～50 条封箱记录")
        List<@Valid CaseSpec> cases
) {

    /**
     * 单条封箱规格：caseKey 批次内唯一；labelNo 须落在批次号段内且全局未占用；
     * quantity 为正整数（件）。
     */
    public record CaseSpec(
            @NotBlank(message = "caseKey 不能为空") String caseKey,
            @NotNull(message = "labelNo 不能为空") Long labelNo,
            @NotNull(message = "quantity 不能为空")
            @Min(value = 1, message = "quantity 必须为正整数") Integer quantity
    ) {
    }
}
