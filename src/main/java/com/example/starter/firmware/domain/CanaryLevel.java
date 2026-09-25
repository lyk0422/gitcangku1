package com.example.starter.firmware.domain;

/**
 * 金丝雀验证级别及样本统计。样本与失败数随设备回执首次进入终态累计，推进不重置。
 *
 * @param releaseId      所属发布单ID
 * @param levelNo        级别序号，从1开始
 * @param ratio          该级别投放比例，取值1~100，各级别严格递增
 * @param minSamples     最小验证样本数，取值1~50
 * @param maxFailureRate 失败率上限，单位百分之一（百分数），取值1~100
 * @param sampleCount    已进入终态（SUCCESS/FAILED）的样本数
 * @param failCount      样本中结果为 FAILED 的数量
 * @param unlocked       是否已解锁，创建时仅第1级解锁
 */
public record CanaryLevel(long releaseId, int levelNo, int ratio, int minSamples, int maxFailureRate,
                          int sampleCount, int failCount, boolean unlocked) {

    /**
     * 失败率是否超过上限（整数交叉相乘，避免浮点误差）。样本为0时不超限。
     */
    public boolean failureRateExceeded() {
        return sampleCount > 0 && failCount * 100 > maxFailureRate * sampleCount;
    }
}
