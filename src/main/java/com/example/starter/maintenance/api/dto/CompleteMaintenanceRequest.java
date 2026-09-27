package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 完成保养请求：提交人不能确认完工，confirmerId 须为另一维护人员；
 * 以某现存读数及其当前修订号为锚点，锚点时间须严格晚于上次保养锚点。
 *
 * @param requestId                全局唯一请求标识（幂等键）
 * @param expectedVersion          设备期望版本号，与当前版本不一致时返回 409
 * @param readingId                锚点读数标识，须已存在
 * @param anchorRevisionNo         锚点读数的当前修订号，与当前修订号不一致时返回 409
 * @param expectedPackageVersion   确认时依据的证据包版本；与当前周期最新版本不一致时返回 409
 * @param expectedTemplateVersion  确认时依据的模板版本；与证据包固化模板版本不一致时返回 409
 * @param confirmerId              完工确认人（维护人员标识），不得等于证据包提交人
 */
public record CompleteMaintenanceRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull @Positive Integer anchorRevisionNo,
        @NotNull @Positive Integer expectedPackageVersion,
        @NotNull @Positive Integer expectedTemplateVersion,
        @NotBlank String confirmerId) {
}
