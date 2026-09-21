package com.example.starter.consent;

import java.time.LocalDateTime;

/**
 * 授权数据记录实体，对应 consent_record 表；记录归属某一代授权，撤回后不物理删除。
 *
 * @param id         主键
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途
 * @param epoch      写入时的授权代次
 * @param recordKey  记录键，同一代内唯一
 * @param payload    记录内容（合成字符串）
 * @param requestId  写入本记录的幂等请求标识
 * @param createdAt  写入时间（本地时间）
 */
public record ConsentRecord(
        Long id,
        String subjectKey,
        Purpose purpose,
        int epoch,
        String recordKey,
        String payload,
        String requestId,
        LocalDateTime createdAt) {
}
