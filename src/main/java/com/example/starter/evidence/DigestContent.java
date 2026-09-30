package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物当前文件内容实体，对应 digest_content 表。
 * 封存时登记；封存后文件可能被改动或恢复，导出前据此重新计算摘要，不直接等同于封存摘要。
 *
 * @param evidenceKey   关联证物业务键
 * @param contentRef    当前内容定位引用
 * @param contentBase64 当前文件内容的 Base-64
 * @param updatedAt     内容最近替换时间（Asia/Shanghai）
 */
public record DigestContent(
        String evidenceKey,
        String contentRef,
        String contentBase64,
        LocalDateTime updatedAt) {
}
