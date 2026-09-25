package com.example.starter.race.api;

import com.example.starter.race.domain.InspectionResult;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 选手当前有效检录状态（按可注入时钟实时判定）。
 *
 * <p>latest 为该选手最近一条检录（取 inspectedAt 最大者）；
 * valid 仅当 latest 为 PASS 且 validUntil 未到（validUntil &gt; 当前时刻）才为 true。
 * 无任何检录时 latest 为 null、result 为 null、valid 为 false。
 *
 * @param raceId             赛事ID
 * @param bib                选手参赛号
 * @param inspectionRequired 赛事是否强制检录
 * @param result             最近检录结果 PASS / FAIL；无检录为 null
 * @param equipmentSerial    最近检录器材序列号；无检录为 null
 * @param inspectedAt        最近检录时刻；无检录为 null
 * @param validUntil         最近 PASS 有效截止时刻；FAIL/无检录为 null
 * @param valid              当前是否存在未过期 PASS
 * @param now                判定所用当前时刻，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InspectionStatusResponse(
        String raceId,
        String bib,
        boolean inspectionRequired,
        InspectionResult result,
        String equipmentSerial,
        Long inspectedAt,
        Long validUntil,
        boolean valid,
        long now
) {
}
