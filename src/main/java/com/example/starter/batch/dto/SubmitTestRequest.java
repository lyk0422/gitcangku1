package com.example.starter.batch.dto;

import com.example.starter.batch.TestOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 提交检验结果请求。testKey 在同一批次内唯一，同内容重放返回原结果，不同内容返回 409。
 * sampleKey 与 consumeAmount 为破坏性检验消耗，必须同时提供或同时缺省；
 * 仅 QUANTIFIED 批可绑定，消耗无论检验合格与否均扣量且不可撤销。
 */
public record SubmitTestRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "testKey 不能为空") String testKey,
        @NotBlank(message = "testItem 不能为空") String testItem,
        @NotNull(message = "result 不能为空，取值为 PASS/FAIL") TestOutcome result,
        @NotBlank(message = "inspector 不能为空") String inspector,
        String sampleKey,
        @Positive(message = "consumeAmount 必须为正整数") Integer consumeAmount
) {
}
