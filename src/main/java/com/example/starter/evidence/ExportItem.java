package com.example.starter.evidence;

/**
 * 导出包明细实体，对应 export_item 表。只追加、不可变；导出时固化版本、摘要与两名见证人。
 *
 * @param id            主键
 * @param packageId     所属导出包业务标识
 * @param evidenceKey   明细证物业务键
 * @param versionNo     明细裁决的封存版本号
 * @param contentRef    导出版本的内容定位引用
 * @param contentSha256 导出版本登记的内容 SHA-256
 * @param witnessIdsCsv 导出时该版本两名见证保管人（按见证顺序逗号分隔）
 */
public record ExportItem(
        Long id,
        String packageId,
        String evidenceKey,
        int versionNo,
        String contentRef,
        String contentSha256,
        String witnessIdsCsv) {
}
