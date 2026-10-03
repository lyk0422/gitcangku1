package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事重分记录列表响应。
 *
 * @param raceId  赛事ID
 * @param records 全部重分记录，按迁移时间与记录ID稳定排列
 */
public record ReclassRecordsResponse(
        String raceId,
        List<ReclassRecordResponse> records
) {
}