package com.example.starter.domain;

import java.math.BigDecimal;

/**
 * 一段连续失联区间；跨相邻航段时，前段尾部缺口与后段头部缺口连成同一段。
 *
 * @param fromSegment    起始航段序号（含）
 * @param toSegment      结束航段序号（含）；大于 fromSegment 表示跨段连续失联
 * @param lossTickMillis 失联时长的精确分子：Σ(缺口刻度长度 × 段行驶毫秒)；
 *                       实际失联毫秒 = lossTickMillis / 10000，分母为 10 的幂，
 *                       必为有限小数，全程不提前取整
 */
public record LinkGap(int fromSegment, int toSegment, long lossTickMillis) {

    /** 精确失联毫秒（有限小数字符串，如 "5000" 或 "5100.5"）。 */
    public String lossMillisText() {
        return BigDecimal.valueOf(lossTickMillis, 4).stripTrailingZeros().toPlainString();
    }
}
