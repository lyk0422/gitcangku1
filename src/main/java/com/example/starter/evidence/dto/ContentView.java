package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 证物当前内容登记结果视图：返回当前内容重算出的 SHA-256，供与封存版本摘要比对。
 *
 * @param evidenceKey   证物业务键
 * @param contentSha256 当前内容字节的 SHA-256 十六进制摘要
 * @param updatedAt     内容登记时间（Asia/Shanghai）
 */
public record ContentView(
        String evidenceKey,
        String contentSha256,
        LocalDateTime updatedAt) {
}
