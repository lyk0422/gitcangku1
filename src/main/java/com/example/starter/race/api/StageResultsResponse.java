package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.StageStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 阶段成绩响应：各阶段最快原始用时F与每名选手的阶段资格；
 * 封榜后返回只读快照内容。
 *
 * @param raceId   赛事ID
 * @param version  榜单对应的版本号
 * @param status   OPEN / SEALED
 * @param sealedAt 封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param stages   按阶段序号升序排列的阶段结果
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StageResultsResponse(
        String raceId,
        int version,
        RaceStatus status,
        Long sealedAt,
        List<StageResultView> stages
) {

    /**
     * 单个阶段结果。
     *
     * @param stageNo   阶段序号
     * @param fastestMs 该阶段全部完整选手的最快原始用时F（毫秒）；无完整选手为 null
     * @param entries   该阶段全部选手条目，按参赛号字典序
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StageResultView(
            int stageNo,
            Long fastestMs,
            List<StageEntryView> entries
    ) {
    }

    /**
     * 选手阶段条目。
     *
     * @param bib                    参赛号
     * @param status                 阶段资格：COMPLETE / OUT / INCOMPLETE
     * @param rawTimeMs              阶段原始用时（毫秒）；INCOMPLETE 为 null
     * @param checkpointCount        该阶段检查点总数
     * @param coveredCheckpointCount 已覆盖检查点数量
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StageEntryView(
            String bib,
            StageStatus status,
            Long rawTimeMs,
            int checkpointCount,
            int coveredCheckpointCount
    ) {
    }
}
