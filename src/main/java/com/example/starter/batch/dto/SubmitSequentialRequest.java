package com.example.starter.batch.dto;

import com.example.starter.batch.SequentialSample;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 合成序贯检验结果提交请求。
 * 结果序号 seq 必须从 1 连续递增（每批最多 10 个），由调用方按前缀显式给出期望序号；
 * 跳号、重复序号或终止后提交均返回 409，被拒绝的请求不占用序号。
 */
public record SubmitSequentialRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "seq 不能为空，取值为 1～10 的连续序号")
        @Min(value = 1, message = "seq 必须从 1 开始")
        @Max(value = 10, message = "每批最多 10 个连续序贯结果")
        Integer seq,
        @NotNull(message = "sample 不能为空，取值为 GOOD/BAD") SequentialSample sample,
        @NotBlank(message = "inspector 不能为空") String inspector
) {
}
