package com.example.starter.calibration.model;

import java.math.BigDecimal;

/**
 * 拟合参考点：提交时冻结的 (x, y) 与逐点残差评分，不再修改。
 *
 * @param id       参考点 ID（自增）
 * @param fitId    所属拟合 ID
 * @param seq      输入顺序，从 0 开始
 * @param x        合成原值 x，最多 6 位小数
 * @param y        标准值 y，最多 6 位小数
 * @param residual 冻结残差评分 y-(a×x+b)，精确值最多 12 位小数
 */
public record FitPoint(
        long id,
        long fitId,
        int seq,
        BigDecimal x,
        BigDecimal y,
        BigDecimal residual) {
}
