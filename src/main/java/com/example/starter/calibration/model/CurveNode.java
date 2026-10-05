package com.example.starter.calibration.model;

import java.math.BigDecimal;

/**
 * 曲线证书节点。每张曲线证书 3～8 个节点，原始值严格递增；创建后不可修改。
 *
 * @param seq       节点序号，从 0 开始按原始值严格递增排列
 * @param raw       节点原始值，最多 6 位小数
 * @param corrected 节点校正值，最多 6 位小数
 */
public record CurveNode(int seq, BigDecimal raw, BigDecimal corrected) {
}
