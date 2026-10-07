package com.example.starter.consent.dto;

import com.example.starter.consent.ErasureTaskStatus;
import com.example.starter.consent.Purpose;

/**
 * 清除任务进度响应：只含计数与状态等元数据，不包含任何记录载荷。
 *
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch          清除任务所属授权代次
 * @param status         状态：PENDING 待清除 / DONE 已完成
 * @param erasedCount    累计已清除载荷条数（仅统计本代，不跨代）
 * @param remainingCount 本代剩余未清除条数
 */
public record ErasureTaskResponse(String subjectKey, Purpose purpose, int epoch,
                                  ErasureTaskStatus status, int erasedCount, int remainingCount) {
}
