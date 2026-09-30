package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 封存登记结果视图：本次生成的封存版本号与内容 SHA-256 摘要。
 *
 * @param evidenceKey   证物业务键
 * @param versionNo     本次封存生成的版本号（同一证物从 1 递增）
 * @param contentSha256 本次内容字节的 SHA-256 十六进制摘要
 * @param createdAt     封存登记时间（Asia/Shanghai）
 */
public record SealedContentView(
        String evidenceKey,
        int versionNo,
        String contentSha256,
        LocalDateTime createdAt) {
}
