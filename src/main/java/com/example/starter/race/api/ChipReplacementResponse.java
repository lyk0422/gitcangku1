package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 芯片更换响应：固化芯片、时刻、裁判与重算前后归属明细。
 *
 * @param replacementId   更换记录ID
 * @param raceId          赛事ID
 * @param bib             参赛号
 * @param oldChip         旧芯片ID
 * @param newChip         新芯片ID
 * @param effectiveAt     新芯片生效时刻，Unix毫秒时间戳（UTC）
 * @param referee         确认裁判
 * @param createdAt       更换登记时间，Unix毫秒时间戳
 * @param version         更换后的赛事版本号
 * @param readingCount    重算覆盖的分段读数总数（实际值）
 * @param reassignedCount 归属发生变化的读数数量（实际值）
 * @param reassignments   归属变化的读数明细，按检查点顺序排列
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChipReplacementResponse(
        String replacementId,
        String raceId,
        String bib,
        String oldChip,
        String newChip,
        long effectiveAt,
        String referee,
        long createdAt,
        int version,
        int readingCount,
        int reassignedCount,
        List<ChipReassignmentResponse> reassignments
) {
}
