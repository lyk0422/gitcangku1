package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量放行请求。每批 1～50 条测量键，整批原子生效。
 *
 * <p>提供 uncertaintyLimit 时走补偿门禁：整批任一测量缺少环境、补偿后超规格
 * 或不确定度超过该上限，整次 422 并稳定列出测量标识。不提供时沿用既有放行规则（409）。
 *
 * @param keys             测量键列表
 * @param uncertaintyLimit 批次不确定度上限（与读数同量纲，十进制字符串），可空
 */
public record ReleaseRequest(List<String> keys, String uncertaintyLimit) {
}
