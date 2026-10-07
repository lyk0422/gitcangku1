package com.example.starter.consent.dto;

import com.example.starter.consent.JointStatus;

/**
 * 共同拥有记录读取响应：双确认生效后返回冻结内容与主体代次。
 *
 * @param recordKey 共同记录键
 * @param subjectA  主体A标识（字典序较小者）
 * @param subjectB  主体B标识（字典序较大者）
 * @param epochA    冻结的主体A研究授权代次
 * @param epochB    冻结的主体B研究授权代次
 * @param version   生效的提案版本
 * @param status    状态，读取成功时恒为 CONFIRMED
 * @param payload   冻结的提案内容（合成字符串）
 */
public record JointRecordReadResponse(
        String recordKey,
        String subjectA,
        String subjectB,
        int epochA,
        int epochB,
        int version,
        JointStatus status,
        String payload) {
}
