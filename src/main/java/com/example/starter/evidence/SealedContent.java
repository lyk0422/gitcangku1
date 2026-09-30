package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 封存版本内容摘要实体，对应 sealed_content 表。记录只追加、不可变：
 * 同一证物版本号从 1 递增，重封存产生新版本，旧版本摘要永久保留。
 *
 * @param id            主键
 * @param evidenceKey   关联证物业务键
 * @param versionNo     封存版本号，同一证物从 1 递增
 * @param contentSha256 封存时内容字节的 SHA-256 十六进制摘要
 * @param createdAt     封存登记时间（Asia/Shanghai）
 */
public record SealedContent(
        Long id,
        String evidenceKey,
        int versionNo,
        String contentSha256,
        LocalDateTime createdAt) {
}
