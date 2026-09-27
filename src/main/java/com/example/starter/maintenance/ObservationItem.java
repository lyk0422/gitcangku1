package com.example.starter.maintenance;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 批量观测提交中的单条观测：按各自提交时刻逐条匹配维护窗口。
 *
 * @param observationId 观测记录唯一标识
 * @param stationId     所属观测站标识（须已登记）
 * @param clusterId     所属冲突簇标识；未归簇为 null
 * @param submittedAtUtc 观测提交时刻（UTC，窗口匹配按此时刻左闭右开）
 * @param payload       观测原始内容原文；命中维护窗口时仍完整保存
 */
public record ObservationItem(
        @NotBlank @Size(max = 64) String observationId,
        @NotBlank @Size(max = 64) String stationId,
        @Size(max = 64) String clusterId,
        @NotNull Instant submittedAtUtc,
        @NotBlank @Size(max = 2048) String payload) {
}
