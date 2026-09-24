package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 复检延期提交请求体。复检人通过 X-Actor-Id 请求头提供。
 *
 * <p>extensionKey 全局唯一，同一键最多生效一次；recheckConclusion 为非空复检结论，
 * 仅“合格”可放行；extendMinutes 为本次顺延分钟，范围 1～43200。
 */
public record ExtensionSubmitRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "extensionKey 不能为空") String extensionKey,
        @NotBlank(message = "recheckConclusion 不能为空") String recheckConclusion,
        @NotNull(message = "extendMinutes 不能为空")
        @Min(value = 1, message = "extendMinutes 必须为 1～43200 的正整数")
        @Max(value = 43200, message = "extendMinutes 必须为 1～43200 的正整数")
        Integer extendMinutes
) {
}
