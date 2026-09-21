package com.example.starter.plan;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 区段占用输入：列车编号、区段 ID 与 UTC 起止时刻（左闭右开）。
 *
 * @param trainNo 列车编号
 * @param sectionId 区段 ID
 * @param startUtc 占用开始时刻（UTC，左闭）
 * @param endUtc 占用结束时刻（UTC，右开），必须晚于开始
 */
public record OccupancyInput(
        @NotBlank String trainNo,
        @NotBlank String sectionId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc) {
}
