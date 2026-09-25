package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 复检延期提交请求。extensionKey 为批次内唯一的延期业务键（最多生效一次）；
 * retestConclusion 为本次复检结论，PASS=合格/FAIL=不合格，仅合格允许提交；
 * extensionMinutes 为本次顺延分钟，1～43200。
 */
public record SubmitExtensionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "extensionKey 不能为空") String extensionKey,
        @NotBlank(message = "retestConclusion 不能为空") String retestConclusion,
        @NotNull(message = "extensionMinutes 不能为空")
        @Min(value = 1, message = "extensionMinutes 必须在 1～43200 之间")
        @Max(value = 43200, message = "extensionMinutes 必须在 1～43200 之间")
        Integer extensionMinutes
) {
}
