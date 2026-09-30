package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 封存版本视图。
 *
 * @param versionNo     版本号
 * @param sealedBy      封存保管人
 * @param contentRef    封存内容定位引用
 * @param contentSha256 封存登记的 SHA-256
 * @param status        版本状态：ACTIVE / SUPERSEDED
 * @param witnesses     该版本见证人（按见证顺序），只追加
 * @param createdAt     封存时间（Asia/Shanghai）
 */
public record DigestVersionView(
        int versionNo,
        String sealedBy,
        String contentRef,
        String contentSha256,
        String status,
        List<String> witnesses,
        LocalDateTime createdAt) {
}
