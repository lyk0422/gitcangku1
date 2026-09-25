package com.example.starter.firmware.domain;

/**
 * 固件灰度发布单。
 *
 * @param id            发布单ID
 * @param version       发布单版本号，从1开始，每次扩量成功加一
 * @param model         目标设备型号
 * @param fromVersion   来源固件版本
 * @param toVersion     目标固件版本，必须与来源版本不同
 * @param ratio         当前生效投放比例，取值0~100，只增不减；金丝雀发布单等于当前已解锁最高级别的比例
 * @param status        状态：ACTIVE 投放中，CANCELLED 已取消，COMPLETED 已完成
 * @param levelCount    金丝雀验证级别总数，0 表示非金丝雀发布单
 * @param unlockedLevel 当前已解锁的最高金丝雀级别，0 表示非金丝雀发布单
 */
public record ReleaseOrder(long id, int version, String model, String fromVersion, String toVersion,
                           int ratio, ReleaseStatus status, int levelCount, int unlockedLevel) {

    /**
     * 是否声明了金丝雀分级验证。
     */
    public boolean isCanary() {
        return levelCount > 0;
    }
}
