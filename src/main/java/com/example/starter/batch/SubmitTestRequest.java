package com.example.starter.batch;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交检验结果请求。
 *
 * @param commandKey 命令幂等键
 * @param testKey    检验幂等键：同一批次内同内容重放返回原结果，内容不同返回 409
 * @param item       检验项，必须属于批次必做项，否则 404
 * @param outcome    PASS/FAIL；任一 FAIL 立即使批次 REJECTED
 * @param inspector  检验人
 */
public record SubmitTestRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String testKey,
        @NotBlank @Size(max = 64) String item,
        @NotNull TestOutcome outcome,
        @NotBlank @Size(max = 64) String inspector) {
}
