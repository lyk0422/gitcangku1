package com.example.starter.evidence.dto;

/**
 * 导出包清单条目视图：建包时封存版本摘要的不可变快照。
 *
 * @param evidenceKey   包内证物业务键
 * @param versionNo     该证物建包时所处的封存版本号
 * @param contentSha256 建包时封存版本登记的 SHA-256 摘要快照
 */
public record ExportItemView(
        String evidenceKey,
        int versionNo,
        String contentSha256) {
}
