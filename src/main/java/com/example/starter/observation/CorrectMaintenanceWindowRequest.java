package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 维护窗口追加更正请求：以一条新的不可变版本行替换既有窗口的时间区间，或撤回既有窗口（retract）。
 * 不修改任何历史窗口行；已被其他更正替换/撤回的窗口不能再次作为更正目标。
 *
 * @param requestId           全局唯一请求标识（幂等去重键）
 * @param windowId            本次更正窗口行的唯一标识（新版本行独立标识）
 * @param stationId           所属观测站标识
 * @param supersedesWindowId  被替换/撤回的既有有效窗口标识
 * @param startAtUtc          更正后窗口开始时刻（UTC，左闭）；撤回时必须为 null
 * @param endAtUtc            更正后窗口结束时刻（UTC，右开）；撤回时必须为 null
 * @param retract             是否仅撤回既有窗口：true 时不带时间区间
 */
public record CorrectMaintenanceWindowRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String windowId,
        @NotBlank @Size(max = 64) String stationId,
        @NotBlank @Size(max = 128) String supersedesWindowId,
        Instant startAtUtc,
        Instant endAtUtc,
        @NotNull Boolean retract) {
}
