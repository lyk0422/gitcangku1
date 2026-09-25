package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 复检延期提交请求。extensionKey 全局唯一、同一键最多生效一次；
 * reinspectionConclusion 为非空复检结论（须为合格）；extendMinutes 为顺延分钟（1～43200）。
 * 复检人通过 X-Actor-Id 请求头提供，必须与该批次原两名批准人都不同。
 */
public record SubmitExtensionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "extensionKey 不能为空") String extensionKey,
        @NotBlank(message = "reinspectionConclusion 不能为空") String reinspectionConclusion,
        @NotNull(message = "extendMinutes 不能为空")
        @Min(value = 1, message = "extendMinutes 必须在 1～43200 之间")
        @Max(value = 43200, message = "extendMinutes 必须在 1～43200 之间") Integer extendMinutes
) {
}
