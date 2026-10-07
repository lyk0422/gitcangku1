package com.example.starter.consent.dto;

import com.example.starter.consent.ErasureStatus;
import com.example.starter.consent.Purpose;

/**
 * 清除任务无载荷进度响应：只含计数与状态，不含任何记录内容。
 *
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch          清除任务所属授权代次
 * @param status         状态：PENDING 待清除 / DONE 已全部清除
 * @param erasedCount    累计已物理清除载荷的记录数，不跨代累计
 * @param remainingCount 该代次尚未清除载荷的记录数
 */
public record ErasureTaskResponse(String subjectKey, Purpose purpose, int epoch, ErasureStatus status,
                                  int erasedCount, int remainingCount) {
}
