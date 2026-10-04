package com.example.starter.blind.dto;

/**
 * 分析视图（仅 ANALYST）：只有随机配对码与两份冻结摘要，
 * 不含参与者编号、盲码、处理代码或采集时刻。
 *
 * @param pairCode   随机配对码
 * @param preDigest  锁定时冻结的 PRE 摘要
 * @param postDigest 锁定时冻结的 POST 摘要
 */
public record AnalysisPairView(
        String pairCode,
        String preDigest,
        String postDigest
) {
}