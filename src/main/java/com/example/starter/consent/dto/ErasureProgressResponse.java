package com.example.starter.consent.dto;

import com.example.starter.consent.ErasureStatus;
import com.example.starter.consent.Purpose;

/**
 * 清除进度响应：无载荷进度视图，仅含状态与累计清除数。
 *
 * @param subjectKey  主体标识（合成字符串）
 * @param purpose     用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch       被清除的授权代次
 * @param status      任务状态：PENDING 待清除 / DONE 已清完
 * @param erasedCount 累计已清除载荷条数（不跨代统计）
 */
public record ErasureProgressResponse(String subjectKey, Purpose purpose, int epoch,
                                      ErasureStatus status, int erasedCount) {
}
