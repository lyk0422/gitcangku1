package com.example.starter.observation.geo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 不可变一致性确认记录：对应 consistency_confirmation 表的一行，确认成功后原子落库，永不修改。
 *
 * <p>记录冻结确认时刻两条观测的完整版本快照（坐标与协方差矩阵）及比较两侧的值，
 * 后续观测变更不影响本记录；保存的是确认关系，不改动源观测。
 *
 * @param confirmationId 全局唯一确认记录标识
 * @param requestId      生成该记录的请求标识
 * @param observationIdA 第一条观测记录标识
 * @param versionA       确认时第一条观测的当前版本号
 * @param xA             第一条观测 X 坐标快照（米，十进制原文）
 * @param yA             第一条观测 Y 坐标快照（米）
 * @param aA             第一条观测协方差 a 快照（平方米）
 * @param bA             第一条观测协方差 b 快照（平方米）
 * @param cA             第一条观测协方差 c 快照（平方米）
 * @param observationIdB 第二条观测记录标识
 * @param versionB       确认时第二条观测的当前版本号
 * @param xB             第二条观测 X 坐标快照（米）
 * @param yB             第二条观测 Y 坐标快照（米）
 * @param aB             第二条观测协方差 a 快照（平方米）
 * @param bB             第二条观测协方差 b 快照（平方米）
 * @param cB             第二条观测协方差 c 快照（平方米）
 * @param u              坐标差 u = xA - xB（米，精确十进制）
 * @param v              坐标差 v = yA - yB（米，精确十进制）
 * @param sumA           两矩阵之和 A = aA + aB（平方米）
 * @param sumB           两矩阵之和 B = bA + bB（平方米）
 * @param sumC           两矩阵之和 C = cA + cB（平方米）
 * @param lhs            比较左侧 C*u^2 - 2*B*u*v + A*v^2（精确乘积，未开根未舍入）
 * @param rhs            比较右侧 9*(A*C - B^2)（精确乘积，未开根未舍入）
 * @param operator       操作者标识
 * @param confirmedAtUtc 确认完成时刻（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsistencyConfirmation(
        String confirmationId,
        String requestId,
        String observationIdA,
        int versionA,
        String xA,
        String yA,
        String aA,
        String bA,
        String cA,
        String observationIdB,
        int versionB,
        String xB,
        String yB,
        String aB,
        String bB,
        String cB,
        String u,
        String v,
        String sumA,
        String sumB,
        String sumC,
        String lhs,
        String rhs,
        String operator,
        Instant confirmedAtUtc) {
}
