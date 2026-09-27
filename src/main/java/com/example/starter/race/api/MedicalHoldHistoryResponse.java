package com.example.starter.race.api;

import java.util.List;

/**
 * 医疗暂停历史查询响应：按开始时刻、暂停ID稳定排列，只读。
 *
 * @param raceId  赛事ID
 * @param version 查询时赛事版本（只读查询不修改版本）
 * @param holds   医疗暂停历史项（含生效中与已恢复）
 */
public record MedicalHoldHistoryResponse(
        String raceId,
        int version,
        List<MedicalHoldHistoryItem> holds
) {
}
