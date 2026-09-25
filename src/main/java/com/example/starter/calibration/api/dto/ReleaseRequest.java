package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量放行请求。每批 1～50 条测量键，整批原子生效。
 * uncertaintyLimit 为放行批次不确定度上限：提供时启用环境门禁——
 * 整批任一测量缺少环境、补偿后超规格或不确定度超过上限则整批 422；
 * 不提供时沿用基础计算值放行（兼容无环境批次）。
 *
 * @param keys             测量键列表
 * @param uncertaintyLimit 批次不确定度上限（非负，最多 6 位小数字符串）；可空
 * @param calcKey          幂等键；同键同指纹重放首次成功结果，失败不占键；可空
 */
public record ReleaseRequest(List<String> keys, String uncertaintyLimit, String calcKey) {
}
