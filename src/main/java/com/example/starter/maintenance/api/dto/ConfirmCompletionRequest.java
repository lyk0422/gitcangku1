package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 双人完工确认请求：另一维护角色确认当前证据包版本后，原子完成保养并重置下一周期。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param confirmedBy      确认人（另一维护角色），不得为当前证据包提交人
 * @param packageVersion   被确认的证据包版本号，须等于当前周期最新版本，否则 409
 * @param templateVersion  确认时模板版本号，须等于模板当前版本且等于证据包快照版本，否则 409
 * @param readingId        保养锚点读数标识，须已存在
 * @param anchorRevisionNo 锚点读数当前修订号，不一致时返回 409
 */
public record ConfirmCompletionRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String confirmedBy,
        @NotNull @Positive Integer packageVersion,
        @NotNull @Positive Integer templateVersion,
        @NotBlank String readingId,
        @NotNull @Positive Integer anchorRevisionNo) {
}
