package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单选手在单个中止事件上的补偿明细。
 *
 * @param eventKey       中止事件ID
 * @param affected       是否受该事件影响（未通过受影响起始检查点且中止开始时仍在赛程中）
 * @param compensationMs 补偿毫秒数：受影响为中止时长，否则为0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompensationEntryResponse(
        String eventKey,
        boolean affected,
        long compensationMs
) {
}
