package com.example.starter.observation;

import java.time.Instant;

/**
 * 人工裁决记录：对应 submission_adjudication 表的一行，成功裁决后原子落库，不可变。
 *
 * @param adjudicationId   全局唯一裁决记录标识
 * @param submissionId     被裁决的提交记录标识
 * @param requestId        生成该裁决的请求标识
 * @param decision         裁决结果：ACCEPT（接受被拒观测）
 * @param reason           裁决理由
 * @param operator         裁决操作者标识
 * @param mergedVersion    裁决接受后合并产生的观测记录版本号
 * @param adjudicatedAtUtc 裁决完成时刻（UTC）
 */
public record AdjudicationRecord(
        String adjudicationId,
        String submissionId,
        String requestId,
        String decision,
        String reason,
        String operator,
        int mergedVersion,
        Instant adjudicatedAtUtc) {
}
