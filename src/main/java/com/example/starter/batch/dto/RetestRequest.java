package com.example.starter.batch.dto;

import com.example.starter.batch.TestOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 召回复检请求体。仅处于召回影响范围（自身 RECALLED 或祖先被召回）的批次可提交；
 * retestKey 批次内幂等，同内容重放返回原结果，不同内容返回 409。
 */
public record RetestRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "retestKey 不能为空") String retestKey,
        @NotNull(message = "outcome 不能为空") TestOutcome outcome,
        @NotBlank(message = "inspector 不能为空") String inspector
) {
}
