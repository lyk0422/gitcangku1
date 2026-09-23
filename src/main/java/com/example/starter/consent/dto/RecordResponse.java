package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

/**
 * 记录响应：返回记录内容与写入时固化的不可变写入依据。
 *
 * <p>{@code delegationPath} 为写入时校验通过的完整委托版本快照，此后委托撤销、续建均不改变该快照；
 * 主体直写时为空列表。
 *
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途
 * @param epoch          记录所属授权代次
 * @param callerKey      实际写入方标识
 * @param recordKey      记录键
 * @param payload        记录内容
 * @param evaluatedAt    写入依据评估时刻（UTC）
 * @param delegationPath 完整委托边版本快照（不可变），主体直写为空列表
 */
public record RecordResponse(
        String subjectKey,
        String purpose,
        int epoch,
        String callerKey,
        String recordKey,
        String payload,
        Instant evaluatedAt,
        List<EdgeBasis> delegationPath) {
}
