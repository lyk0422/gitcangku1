package com.example.starter.spectrum.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 频率方案提交：1~20 个不重复台站的新频道（或静默值0），携带 expectedVersion 与全局唯一 planKey。
 */
public record PlanSubmitRequest(
        @NotNull(message = "expectedVersion 不能为空")
        @Min(value = 1, message = "expectedVersion 必须为正整数")
        Integer expectedVersion,
        @NotBlank(message = "planKey 不能为空")
        String planKey,
        @NotEmpty(message = "assignments 至少包含1个台站")
        @Valid
        List<AssignmentInput> assignments
) {
    /**
     * 单台站频道指派：channel=0 表示静默，1~8 为频道号；未列台站保持原状态。
     */
    public record AssignmentInput(
            @NotBlank(message = "stationId 不能为空")
            String stationId,
            @NotNull(message = "channel 不能为空")
            @Min(value = 0, message = "channel 范围为0~8，0表示静默")
            @Max(value = 8, message = "channel 范围为0~8，0表示静默")
            Integer channel
    ) {
    }
}
