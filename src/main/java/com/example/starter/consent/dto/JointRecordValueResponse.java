package com.example.starter.consent.dto;

import com.example.starter.consent.JointStatus;

/**
 * 共同研究记录含值读取响应：仅在双方授权有效且当前提案已双确认时返回。
 *
 * @param recordKey     共同记录键
 * @param subjectA      主体A标识（合成字符串）
 * @param epochA        主体A授权代次，创建时冻结，重新授权不替换
 * @param subjectB      主体B标识（合成字符串）
 * @param epochB        主体B授权代次，创建时冻结，重新授权不替换
 * @param version       当前提案版本，从 1 开始
 * @param revisionCount 已修订次数，最多 3 次
 * @param status        状态：PROPOSED 待双确认 / FROZEN 当前版本已双确认冻结
 * @param payload       冻结的提案内容（合成字符串）
 */
public record JointRecordValueResponse(String recordKey, String subjectA, int epochA,
                                       String subjectB, int epochB,
                                       int version, int revisionCount, JointStatus status,
                                       String payload) {
}
