package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 未晋级清单响应：已划入分组但不在当前生效名单中的选手；
 * 成绩状态为查询时实时计算（名单本身已冻结）。
 *
 * @param raceId         赛事ID
 * @param advancementKey 当前生效名单键
 * @param entries        未晋级选手（按分组代码、参赛号字典序）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NonAdvancedResponse(
        String raceId,
        String advancementKey,
        List<NonAdvancedEntryResponse> entries
) {

    /**
     * 未晋级选手条目。
     *
     * @param bib          参赛号
     * @param groupCode    所属分组代码
     * @param status       查询时的成绩状态（RANKED/UNTIMED/MISSING_CHECKPOINT/DISQUALIFIED）
     * @param rank         查询时的总排名；未排名为 null
     * @param totalTimeMs  查询时的总耗时（毫秒）；未排名为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NonAdvancedEntryResponse(
            String bib,
            String groupCode,
            EntryStatus status,
            Integer rank,
            Long totalTimeMs
    ) {
    }
}
