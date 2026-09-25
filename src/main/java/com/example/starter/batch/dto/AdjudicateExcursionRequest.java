package com.example.starter.batch.dto;

import com.example.starter.batch.ExcursionDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * MAJOR 储运偏差裁决请求；结论只能为 REWORK 或 REJECT。
 *
 * @param decision REWORK：原批次置 REWORKED 并沿返工链创建返工子批；
 *                 REJECT：本批次置 DISPOSED，全部后代按召回口径拦截
 * @param reworkBatchKey 裁决为 REWORK 时必填：返工子批业务键，全局唯一
 * @param reworkBatchNo  裁决为 REWORK 时必填：返工子批号
 */
public record AdjudicateExcursionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "decision 只能为 REWORK 或 REJECT") ExcursionDecision decision,
        String reworkBatchKey,
        String reworkBatchNo
) {
}
