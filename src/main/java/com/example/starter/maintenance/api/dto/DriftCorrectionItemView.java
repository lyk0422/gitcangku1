package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 漂移修正影响明细视图：区间内每条受影响读数的旧值、新值与插值段。
 *
 * @param seq                   明细序号，按读数采样时刻升序从 1 开始
 * @param readingId             受影响读数标识
 * @param sampledAt             读数的 UTC 采样时刻
 * @param segmentIndex          插值段序号：读数所在锚点段的左锚点 seq（末锚点归属最后一段）
 * @param oldCumulativeMinutes  修正前累计工时（分钟），毫秒值四舍五入视图
 * @param oldCumulativeMillis   修正前累计工时（毫秒），规范精确值
 * @param newCumulativeMillis   修正后累计工时（毫秒），按时间线性插值并舍入到 0.001 小时
 * @param newCumulativeHours    修正后累计工时（小时，固定 3 位小数的十进制字符串）
 * @param oldRevisionNo         修正前读数修订号
 * @param newRevisionNo         修正后读数修订号（预览时为投影值 oldRevisionNo + 1）
 */
public record DriftCorrectionItemView(
        int seq,
        String readingId,
        Instant sampledAt,
        int segmentIndex,
        long oldCumulativeMinutes,
        long oldCumulativeMillis,
        long newCumulativeMillis,
        String newCumulativeHours,
        int oldRevisionNo,
        int newRevisionNo) {
}
