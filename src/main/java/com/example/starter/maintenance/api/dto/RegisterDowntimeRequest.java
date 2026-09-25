package com.example.starter.maintenance.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 登记停机区间请求。区间左闭右开 [startAt, endAt)，结束须晚于开始；
 * 起止须落在设备最早与最晚读数采样时刻之间，且不得跨越任何已有保养锚点时刻（违反返回 422）。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param downtimeKey      停机业务键，全局唯一（含已撤销记录，不可复用）
 * @param startAt          停机开始时刻（UTC，含）
 * @param endAt            停机结束时刻（UTC，不含）
 * @param reason           停机原因，非空
 */
public record RegisterDowntimeRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String downtimeKey,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotBlank String reason) {
}
