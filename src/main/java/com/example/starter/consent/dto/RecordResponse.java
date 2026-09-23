package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 记录响应：返回记录内容及不可变写入依据。
 *
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch          记录所属授权代次
 * @param recordKey      记录键
 * @param payload        记录内容（合成字符串）
 * @param delegationPath 写入时使用的处理方键有序链，主体直写为空列表
 * @param edgeVersions   写入时使用的委托链各边版本，主体直写为空列表
 * @param evaluatedAt    同一事务快照内的授权评估时刻（UTC）
 */
public record RecordResponse(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload,
                             List<String> delegationPath, List<Integer> edgeVersions, Instant evaluatedAt) {
}
