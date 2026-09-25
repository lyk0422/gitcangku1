package com.example.starter.firmware.domain;

/**
 * 金丝雀分级验证级别。样本与失败计数随首次终结回执累计，推进后不重置。
 *
 * @param id             级别ID
 * @param releaseId      所属发布单ID
 * @param levelNo        级别序号，从1开始递增
 * @param ratio          该级别投放比例，取值1~100，同一发布单内逐级递增
 * @param minSamples     推进所需最小验证样本数，取值1~50
 * @param maxFailureRate 失败率上限，单位百分比，取值1~100；超过即触发失败自动暂停
 * @param sampleCount    该级别已计入的首次终结回执样本数
 * @param failedCount    该级别样本中首次结果为FAILED的数量
 */
public record CanaryLevel(long id, long releaseId, int levelNo, int ratio, int minSamples,
                          int maxFailureRate, int sampleCount, int failedCount) {

    /**
     * 失败率是否超过上限（整数运算，避免浮点误差）。
     */
    public boolean failureRateExceeded() {
        return sampleCount > 0 && failedCount * 100L > (long) maxFailureRate * sampleCount;
    }

    /**
     * 失败率百分比，保留两位小数；无样本时为0。
     */
    public double failureRatePercent() {
        if (sampleCount == 0) {
            return 0.0;
        }
        return Math.round(failedCount * 10000.0 / sampleCount) / 100.0;
    }
}
