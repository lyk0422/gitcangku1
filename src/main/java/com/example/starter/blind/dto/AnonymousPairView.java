package com.example.starter.blind.dto;

/**
 * 分析人匿名配对视图：只有随机配对码与两份冻结摘要；
 * 严禁包含参与者编号、盲码、席位号或处理代码。
 *
 * @param pairCode   随机无含义配对码
 * @param preDigest  锁定时冻结的 PRE 摘要
 * @param postDigest 锁定时冻结的 POST 摘要
 */
public record AnonymousPairView(
        String pairCode,
        String preDigest,
        String postDigest
) {
}