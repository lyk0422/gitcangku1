package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 选手当前器材检录有效状态查询响应。
 *
 * @param raceId       赛事ID
 * @param bib          参赛号
 * @param mandatory    赛事是否强制检录；false 时 effective 恒为 NOT_REQUIRED
 * @param effective    当前有效性：PASS_VALID-未过期PASS可起跑，FAIL-最近结果FAIL，
 *                     EXPIRED-最近PASS已过期，NONE-从无检录记录，NOT_REQUIRED-非强制赛事
 * @param latest       最近一条检录记录；从无检录时为 null
 * @param nowMillis    服务端当前时刻，Unix毫秒时间戳（可注入时钟）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InspectionStatusResponse(
        String raceId,
        String bib,
        boolean mandatory,
        String effective,
        InspectionRecordResponse latest,
        long nowMillis
) {
}
