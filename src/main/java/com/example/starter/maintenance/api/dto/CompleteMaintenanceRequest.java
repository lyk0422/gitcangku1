package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 完成保养请求：以某现存读数及其当前修订号为锚点，锚点时间须严格晚于该项目上次保养锚点。
 * 不传 itemCode 时沿用旧接口语义，操作 DEFAULT 项目；同一读数可作为不同项目的锚点。
 *
 * @param requestId         全局唯一请求标识（幂等键）
 * @param expectedVersion   设备期望版本号，与当前版本不一致时返回 409
 * @param readingId         锚点读数标识，须已存在
 * @param anchorRevisionNo  锚点读数的当前修订号，与当前修订号不一致时返回 409
 * @param itemCode          保养项目编码；为空时按 DEFAULT 处理
 */
public record CompleteMaintenanceRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull @Positive Integer anchorRevisionNo,
        @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+",
                message = "只能包含字母、数字、下划线、连字符")
        String itemCode) {
}
