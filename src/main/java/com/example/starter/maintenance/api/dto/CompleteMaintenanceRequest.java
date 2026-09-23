package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 完成保养请求：以某现存读数及其当前修订号为指定项目的锚点，
 * 锚点时间在该项目内须严格晚于其上次保养锚点。
 *
 * @param requestId         全局唯一请求标识（幂等键）
 * @param expectedVersion   设备期望版本号，与当前版本不一致时返回 409
 * @param itemCode          保养项目编码；为空时按 DEFAULT 项目处理（旧接口兼容）
 * @param readingId         锚点读数标识，须已存在
 * @param anchorRevisionNo  锚点读数的当前修订号，与当前修订号不一致时返回 409
 */
public record CompleteMaintenanceRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        String itemCode,
        @NotBlank String readingId,
        @NotNull @Positive Integer anchorRevisionNo) {
}
