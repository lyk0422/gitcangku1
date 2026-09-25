package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 车底交路链中的一段（同车底、同运营日的一张已发布计划），按始发时刻升序。
 *
 * @param gapMinutes 与上一段的实际周转间隔（前段终到至本段始发的分钟数）；链首为 null
 * @param requiredMinutes 上一段→本段要求的最小周转分钟数；链首为 null
 * @param linked 与上一段是否连续（站点衔接且周转满足）；链首为 true
 * @param chainBreak 上一段→本段之间是否存在断链记录（如中间段被取消）
 * @param rearrangePending 待重排标记
 */
public record StockChainItem(String scheduleKey, String opDate, String status,
                             String originStation, String destinationStation,
                             Instant startUtc, Instant endUtc,
                             Long gapMinutes, Long requiredMinutes, boolean linked,
                             boolean chainBreak, boolean rearrangePending, int version) {
}
