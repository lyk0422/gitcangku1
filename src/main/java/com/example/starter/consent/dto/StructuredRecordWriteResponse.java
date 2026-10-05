package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 结构化记录写入响应：只返回记录标识与版本元数据，不回显字段内容，
 * 因此重放写入响应也不会泄露任何字段值。
 *
 * @param subjectKey         主体标识（合成字符串）
 * @param purpose            用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch              记录所属授权代次
 * @param recordKey          记录键
 * @param restrictionVersion 写入时当前生效的限制清单版本，未设置任何限制为 0
 */
public record StructuredRecordWriteResponse(
        String subjectKey, Purpose purpose, int epoch, String recordKey, int restrictionVersion) {
}
