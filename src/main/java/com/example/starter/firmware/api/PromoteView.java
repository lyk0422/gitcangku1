package com.example.starter.firmware.api;

/**
 * 金丝雀推进结果视图。
 *
 * @param releaseId    发布单ID
 * @param action       动作：UNLOCKED 解锁下一级别，COMPLETED 进入完成终态
 * @param currentLevel 推进后当前已解锁最高级别
 * @param ratio        推进后生效投放比例
 * @param status       推进后发布单状态
 */
public record PromoteView(long releaseId, String action, int currentLevel, int ratio, String status) {
}
