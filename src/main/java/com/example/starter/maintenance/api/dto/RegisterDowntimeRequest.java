package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记停机区间请求。区间左闭右开，结束严格晚于开始；
 * 同设备生效区间不得重叠（仅端点相接合法）；起止须落在最早与最晚读数采样时刻之间；
 * 不得跨越已有保养锚点时刻。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param downtimeKey      停机区间全局唯一标识
 * @param startAt          UTC 开始时刻（含）
 * @param endAt            UTC 结束时刻（不含），严格晚于开始时刻
 * @param reason           停机原因，非空
 */
public record RegisterDowntimeRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String downtimeKey,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotBlank @Size(max = 512) String reason) {
}
