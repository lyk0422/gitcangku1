package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 偏差裁决请求体。MAJOR 偏差 disposition 只能为 REWORK 或 REJECT；
 * MINOR 偏差由质控 CONFIRM 确认（须 X-Approval-Role: QUALITY）。
 * REWORK 时 reworkBatchKey（新返工批全局业务键）与 reworkBatchNo 必填；
 * REWORK/REJECT 时 reason 必填。
 */
public record AdjudicateExcursionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "disposition 不能为空") String disposition,
        String reason,
        String reworkBatchKey,
        String reworkBatchNo
) {
}
