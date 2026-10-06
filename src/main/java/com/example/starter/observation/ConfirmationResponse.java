package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 一致性确认响应：返回不可变确认记录的完整冻结内容。
 * 十进制值以字符串原文返回，单位：坐标与坐标差米、矩阵元素及矩阵和平方米。
 *
 * @param confirmationId      全局唯一确认记录标识
 * @param requestId           生成该记录的请求标识
 * @param firstObservationId  第一条观测点唯一标识
 * @param firstVersion        确认时第一条观测点的版本号
 * @param secondObservationId 第二条观测点唯一标识
 * @param secondVersion       确认时第二条观测点的版本号
 * @param first               第一条观测点冻结的坐标与矩阵
 * @param second              第二条观测点冻结的坐标与矩阵
 * @param diffU               坐标差 u（精确十进制原文）
 * @param diffV               坐标差 v（精确十进制原文）
 * @param sumA                矩阵和元素 A（精确十进制原文）
 * @param sumB                矩阵和元素 B（精确十进制原文）
 * @param sumC                矩阵和元素 C（精确十进制原文）
 * @param lhsValue            比较左侧精确值
 * @param rhsValue            比较右侧精确值
 * @param operator            操作者标识
 * @param confirmedAtUtc      确认完成时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfirmationResponse(
        String confirmationId,
        String requestId,
        String firstObservationId,
        int firstVersion,
        String secondObservationId,
        int secondVersion,
        PositionedObservationResponse first,
        PositionedObservationResponse second,
        String diffU,
        String diffV,
        String sumA,
        String sumB,
        String sumC,
        String lhsValue,
        String rhsValue,
        String operator,
        Instant confirmedAtUtc) {

    /**
     * 由不可变确认记录构造响应。
     */
    public static ConfirmationResponse of(ConfirmationRecord record) {
        return new ConfirmationResponse(
                record.confirmationId(),
                record.requestId(),
                record.firstObservationId(),
                record.firstVersion(),
                record.secondObservationId(),
                record.secondVersion(),
                PositionedObservationResponse.of(record.first()),
                PositionedObservationResponse.of(record.second()),
                record.diffU().toPlainString(),
                record.diffV().toPlainString(),
                record.sumA().toPlainString(),
                record.sumB().toPlainString(),
                record.sumC().toPlainString(),
                record.lhsValue().toPlainString(),
                record.rhsValue().toPlainString(),
                record.operator(),
                record.confirmedAtUtc());
    }
}
