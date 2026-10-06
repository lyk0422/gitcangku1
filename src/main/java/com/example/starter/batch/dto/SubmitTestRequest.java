package com.example.starter.batch.dto;

import com.example.starter.batch.TestOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 提交检验结果请求。testKey 在同一批次内唯一，同内容重放返回原结果，不同内容返回 409。
 * sampleKey 与 consumeQuantity 可选且必须成对出现：仅新类型数量批次可在提交检验时
 * 绑定正整数消耗量；无论检验合格与否都扣量，消费不可撤销。
 */
public record SubmitTestRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "testKey 不能为空") String testKey,
        @NotBlank(message = "testItem 不能为空") String testItem,
        @NotNull(message = "result 不能为空，取值为 PASS/FAIL") TestOutcome result,
        @NotBlank(message = "inspector 不能为空") String inspector,
        String sampleKey,
        @Positive(message = "consumeQuantity 必须为正整数") Integer consumeQuantity
) {
}
