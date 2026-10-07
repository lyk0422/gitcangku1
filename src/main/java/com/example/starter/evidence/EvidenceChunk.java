package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 分块实体，对应 evidence_chunk 表。块写入后不可修改、不可删除。
 *
 * @param id      主键
 * @param intakeKey 所属分块接收业务键
 * @param offset  块起始偏移（字节，非负，相对完整字节流）
 * @param length  解码后字节长度（1..4096）
 * @param payload Base64 解码后的原始合成字节，禁止写入应用日志
 * @param createdAt 块首次接收时间（Asia/Shanghai）
 */
public record EvidenceChunk(
        Long id,
        String intakeKey,
        int offset,
        int length,
        byte[] payload,
        LocalDateTime createdAt) {
}
