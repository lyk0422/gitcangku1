package com.example.starter.plan.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 线路图整体替换请求。requestId 为幂等键；expectedVersion 必须等于当前图版本
 * （尚未建图时为 0），成功一次版本加一。区段 1～20 条。
 */
public record ReplaceGraphRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @Min(0) int expectedVersion,
        @NotNull @Size(min = 1, max = 20) List<@Valid GraphSegmentRequest> segments) {
}
