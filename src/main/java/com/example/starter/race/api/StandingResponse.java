package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 成绩榜响应：即时成绩或封榜只读快照。
 *
 * @param raceId   赛事ID
 * @param version  榜单对应的版本号
 * @param status   OPEN / SEALED
 * @param sealedAt 封榜时间，Unix毫秒时间戳；未封榜为 null
 * @param entries  按展示顺序排列的成绩条目
 * @param protests 封榜时固化的申诉与裁决依据；OPEN 即时视图为空列表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StandingResponse(
        String raceId,
        int version,
        RaceStatus status,
        Long sealedAt,
        List<ResultEntryResponse> entries,
        List<SnapshotProtestResponse> protests
) {

    /** 不含申诉依据的构造器（OPEN 即时成绩）。 */
    public StandingResponse(
            String raceId,
            int version,
            RaceStatus status,
            Long sealedAt,
            List<ResultEntryResponse> entries) {
        this(raceId, version, status, sealedAt, entries, List.of());
    }
}
