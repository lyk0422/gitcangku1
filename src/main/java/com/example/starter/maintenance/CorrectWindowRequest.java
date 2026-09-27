package com.example.starter.maintenance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 追加维护更正窗口请求：用于已被合并快照封存、不可修改的历史窗口，只能追加不可改原窗。
 *
 * @param requestId         全局唯一请求标识（幂等去重键）
 * @param windowId          新更正窗口唯一标识
 * @param correctsWindowId  被更正的原窗口标识（须属于同一站点）
 * @param startAtUtc        更正窗口开始时刻（UTC，含）
 * @param endAtUtc          更正窗口结束时刻（UTC，不含）
 */
public record CorrectWindowRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String windowId,
        @NotBlank @Size(max = 64) String correctsWindowId,
        @NotNull Instant startAtUtc,
        @NotNull Instant endAtUtc) {
}
