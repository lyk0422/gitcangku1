package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 完成保养请求：以某现存读数及其当前修订号为锚点，锚点时间须严格晚于上次保养锚点。
 * 完成时冻结锚点读数标识、物理累计快照与当时的等效累计快照。
 *
 * @param requestId         全局唯一请求标识（幂等键）
 * @param operator          操作者标识（可选，纳入幂等身份）
 * @param expectedVersion   设备期望版本号，与当前版本不一致时返回 409
 * @param readingId         锚点读数标识，须已存在
 * @param anchorRevisionNo  锚点读数的当前修订号，与当前修订号不一致时返回 409
 */
public record CompleteMaintenanceRequest(
        @NotBlank String requestId,
        String operator,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull @Positive Integer anchorRevisionNo) {
}
