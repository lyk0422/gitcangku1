package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 选手芯片历史查询响应（只读）：当前芯片与按提交顺序排列的全部更换记录。
 *
 * @param raceId       所属赛事ID
 * @param bib          选手参赛号
 * @param version      查询时赛事版本（只读查询不推进版本）
 * @param currentChipCode 选手当前芯片代码；尚未发放为 null
 * @param replacements 更换历史，按提交顺序稳定返回；从不随后续操作改写
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChipHistoryResponse(
        String raceId,
        String bib,
        int version,
        String currentChipCode,
        List<ChipReplacementResponse> replacements
) {
}
