package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 受限批量查询成功响应快照：成功结果整体作为幂等重放快照，字段顺序与含义保持稳定。
 *
 * @param requestId        本次查询的幂等请求标识
 * @param subjectKey       主体标识
 * @param purpose          查询用途
 * @param epoch            实际服务的授权代次
 * @param quantity         实际返回对象数（＝规范化集合大小＝本次核销对象数）
 * @param remainingBalance 扣减后剩余余额（单位：对象数）
 * @param records          查询结果，按对象键字典序稳定排序
 */
public record PurposeQueryResponse(String requestId, String subjectKey, Purpose purpose, int epoch,
                                   int quantity, long remainingBalance, List<QueryRecordItem> records) {
}
