package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 批量检验预约成功响应：返回实际创建条数（要求全部成功）与全部预约明细快照（按证物、台位、时段稳定排序）。
 *
 * @param reservedCount 实际创建预约数（实际值）
 * @param reservations  创建成功的预约明细
 */
public record ExamReserveResultView(
        int reservedCount,
        List<ExamReservationView> reservations) {
}
