package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事退赛清单响应（含已撤销记录，按登记时间稳定排列）。
 *
 * @param raceId      赛事ID
 * @param version     查询时的赛事版本号
 * @param withdrawals 全部退赛登记
 */
public record WithdrawalListResponse(
        String raceId,
        int version,
        List<WithdrawalResponse> withdrawals
) {
}
