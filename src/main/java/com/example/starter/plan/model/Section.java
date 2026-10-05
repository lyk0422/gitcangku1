package com.example.starter.plan.model;

/**
 * 线路区段注册信息。限速令仅可登记在已注册区段上；
 * 未注册区段的计划占用不参与限速判定。
 *
 * @param sectionId          区段 ID，全局唯一
 * @param lineId             所属线路 ID
 * @param lengthKm           区段长度，单位公里，用于按占用时长推算计划运行速度
 * @param minIntervalMinutes 线路既有最小间隔，单位分钟；限速重排按该间隔的整数倍整体顺延
 */
public record Section(String sectionId, String lineId, double lengthKm, int minIntervalMinutes) {
}
