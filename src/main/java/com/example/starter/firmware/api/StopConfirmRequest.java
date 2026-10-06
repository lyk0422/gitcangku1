package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 停止确认请求：设备确认已停止安装，仅 CANCEL_REQUESTED 任务可受理；
 * generation 必须与任务当前取消代次一致，确认后任务转 CANCELLED 并释放设备独占安装槽。
 * operator 为非空操作者；expectedVersion 为期望的发布单版本，不一致返回 409。
 */
public record StopConfirmRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull @Min(1) Integer generation) {
}
