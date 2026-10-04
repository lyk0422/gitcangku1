package com.example.starter.blind.dto;

/**
 * SUPPLY 供应查询视图：只返回包的发放状态，不含处理代码、包装码、参与者等任何可解码信息。
 *
 * @param kitNo     包编号
 * @param status    发放状态：STOCK=在库；ISSUED=已发放（发过的包不回库）
 * @param issuedAt  发放时间，Unix 毫秒，UTC；null 表示尚未发放
 */
public record KitStatusView(
        String kitNo,
        String status,
        Long issuedAt
) {
}