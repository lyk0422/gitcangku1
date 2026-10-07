package com.example.starter.consent.dto;

import com.example.starter.consent.JointStatus;

/**
 * 共同拥有记录写操作响应：仅返回提案元数据，不包含提案内容。
 *
 * @param recordKey         共同记录键
 * @param subjectA          主体A标识（字典序较小者）
 * @param subjectB          主体B标识（字典序较大者）
 * @param epochA            冻结的主体A研究授权代次
 * @param epochB            冻结的主体B研究授权代次
 * @param version           当前提案版本，从 1 开始
 * @param revisionCount     已完成内容修订次数，最多 3 次
 * @param status            状态：PENDING 待双确认 / CONFIRMED 已双确认生效
 * @param subjectAConfirmed 主体A是否已确认当前提案版本
 * @param subjectBConfirmed 主体B是否已确认当前提案版本
 */
public record JointRecordResponse(
        String recordKey,
        String subjectA,
        String subjectB,
        int epochA,
        int epochB,
        int version,
        int revisionCount,
        JointStatus status,
        boolean subjectAConfirmed,
        boolean subjectBConfirmed) {
}
