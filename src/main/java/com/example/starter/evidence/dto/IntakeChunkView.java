package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 已接收块视图（只读块清单项）。不暴露块载荷，仅提供偏移、长度与块摘要。
 *
 * @param intakeKey   关联分块入库业务键
 * @param offsetBytes 块起始偏移（字节）
 * @param lengthBytes 解码后字节长度
 * @param chunkSha256 块解码字节的 SHA-256 摘要（十六进制小写）
 * @param createdAt   块接收时间（Asia/Shanghai）
 */
public record IntakeChunkView(
        String intakeKey,
        int offsetBytes,
        int lengthBytes,
        String chunkSha256,
        LocalDateTime createdAt) {
}
