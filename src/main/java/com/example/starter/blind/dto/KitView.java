package com.example.starter.blind.dto;

/**
 * SUPPLY 登记处理包的响应视图；仅面向可信 SUPPLY 角色，不下发给协调员。
 *
 * @param experimentId 实验编号
 * @param kitNo        包编号，同一实验内唯一
 * @param treatment    处理代码 A/B，仅 SUPPLY 可见
 * @param packageCode  系统生成的随机无含义包装码，与包编号、处理代码无对应规律
 * @param status       发放状态：STOCK=在库；ISSUED=已发放
 * @param registeredAt 登记时间，Unix 毫秒，UTC
 */
public record KitView(
        String experimentId,
        String kitNo,
        String treatment,
        String packageCode,
        String status,
        long registeredAt
) {
}