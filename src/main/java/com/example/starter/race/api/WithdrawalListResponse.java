package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 退赛清单：赛事下全部退赛历史（含已撤销，撤销记录不可变），按登记先后排列。
 *
 * @param raceId      赛事ID
 * @param version     查询时的赛事版本（只读查询不修改版本）
 * @param withdrawals 退赛登记列表；已撤销条目的 revoked=true 仍保留
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WithdrawalListResponse(
        String raceId,
        int version,
        List<WithdrawalResponse> withdrawals
) {
}
