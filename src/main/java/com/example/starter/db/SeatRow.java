package com.example.starter.db;

/**
 * experiment_seat 表行：实验固定席位与处理代码映射，创建后不可改。
 *
 * @param experimentId  实验编号
 * @param blockNo       区组号（从 1 开始）
 * @param seatNo        区内席位序号（1～4，顺序提交用）
 * @param treatmentCode 处理代码 A/B，仅存数据库
 */
public record SeatRow(
        String experimentId,
        int blockNo,
        int seatNo,
        String treatmentCode
) {
}
