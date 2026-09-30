package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 当前内容替换结果视图。
 *
 * @param evidenceKey   证物业务键
 * @param contentRef    新内容定位引用
 * @param contentSha256 新当前内容重算的 SHA-256（不落封存版本）
 * @param replacedAt    替换时间（Asia/Shanghai）
 */
public record ContentReplacedView(
        String evidenceKey,
        String contentRef,
        String contentSha256,
        LocalDateTime replacedAt) {
}
