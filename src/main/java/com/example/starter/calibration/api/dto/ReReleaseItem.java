package com.example.starter.calibration.api.dto;

/**
 * 重新放行映射单项。驳回项使用最新修订、未驳回项使用原测量，逐项附带版本快照。
 *
 * @param position       批次内位置（从 1 开始），须与原批次全部位置精确一一对应
 * @param measurementKey 该位置采用的测量业务键（驳回项为最新修订键，未驳回项为原测量键）
 * @param version        提交时该测量的版本；版本已变化则整批失败
 */
public record ReReleaseItem(Integer position, String measurementKey, Integer version) {
}
