package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 不可变一致性确认记录：对应 observation_confirmation 表的一行，成功确认后原子落库，永不修改。
 * 冻结两条观测点当时的坐标、矩阵与比较两侧值；后续对观测点的修改不影响本记录。
 *
 * @param confirmationId      全局唯一确认记录标识
 * @param requestId           生成该记录的请求标识
 * @param firstObservationId  第一条观测点唯一标识
 * @param firstVersion        确认时第一条观测点的当前版本号
 * @param secondObservationId 第二条观测点唯一标识
 * @param secondVersion       确认时第二条观测点的当前版本号
 * @param first               第一条观测点冻结的坐标与矩阵快照
 * @param second              第二条观测点冻结的坐标与矩阵快照
 * @param diffU               坐标差 u = first.x - second.x，单位米（精确十进制）
 * @param diffV               坐标差 v = first.y - second.y，单位米（精确十进制）
 * @param sumA                两矩阵之和元素 A，单位平方米（精确十进制）
 * @param sumB                两矩阵之和元素 B，单位平方米（精确十进制）
 * @param sumC                两矩阵之和元素 C，单位平方米（精确十进制）
 * @param lhsValue            不等式左侧 C*u^2 - 2*B*u*v + A*v^2 的精确值（比较左侧）
 * @param rhsValue            不等式右侧 9*(A*C - B^2) 的精确值（比较右侧）
 * @param operator            操作者标识
 * @param confirmedAtUtc      确认完成时刻（UTC）
 */
public record ConfirmationRecord(
        String confirmationId,
        String requestId,
        String firstObservationId,
        int firstVersion,
        String secondObservationId,
        int secondVersion,
        PositionedObservationSnapshot first,
        PositionedObservationSnapshot second,
        BigDecimal diffU,
        BigDecimal diffV,
        BigDecimal sumA,
        BigDecimal sumB,
        BigDecimal sumC,
        BigDecimal lhsValue,
        BigDecimal rhsValue,
        String operator,
        Instant confirmedAtUtc) {
}
