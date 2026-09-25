package com.example.starter.race.api;

import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.RaceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 接力排名响应：即时排名或封榜固化的最终名次。
 *
 * @param raceId   赛事ID
 * @param version  榜单对应的版本号
 * @param status   OPEN / SEALED
 * @param sealedAt 封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param entries  按展示顺序排列的队伍排名条目
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelayStandingResponse(
        String raceId,
        int version,
        RaceStatus status,
        Long sealedAt,
        List<Entry> entries
) {

    /**
     * 队伍排名条目。
     *
     * @param teamKey     队伍标识
     * @param rank        名次；未排名为 null
     * @param status      RANKED / UNTIMED / DISQUALIFIED
     * @param totalMillis 接力总用时（毫秒）；未排名为 null
     * @param foulCount   犯规次数
     * @param hasFouls    是否存在犯规标注（犯规次数大于0但不取消资格，除非达到2次）
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(
            String teamKey,
            Integer rank,
            EntryStatus status,
            Long totalMillis,
            int foulCount,
            boolean hasFouls
    ) {
    }
}
