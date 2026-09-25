package com.example.starter.batch.dto;

import com.example.starter.batch.TestOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 复检提交请求。仅处于召回上下文（自身或任一祖先存在 ACTIVE 召回）的批次可提交；
 * 同一批次同一召回代次同一检验项仅一条，同内容重放返回原结果，不同内容返回 409。
 */
public record ReinspectionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "testItem 不能为空") String testItem,
        @NotNull(message = "outcome 不能为空，取值为 PASS/FAIL") TestOutcome outcome,
        @NotBlank(message = "inspector 不能为空") String inspector
) {
}
