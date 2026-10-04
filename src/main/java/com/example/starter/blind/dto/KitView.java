package com.example.starter.blind.dto;

/**
 * 处理包供应视图：只暴露包编号与发放状态，不包含处理代码、分配主键等可解码信息。
 *
 * @param experimentId 实验编号
 * @param packageNo    处理包编号
 * @param status       STOCK=在库未发 / ISSUED=已发放（终态）
 * @param registeredAt 登记时间，Unix 毫秒，UTC
 * @param issuedAt     发放时间，Unix 毫秒，UTC；null 表示未发放
 */
public record KitView(
        String experimentId,
        String packageNo,
        String status,
        long registeredAt,
        Long issuedAt
) {
}