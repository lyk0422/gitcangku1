package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 导出包明细视图：版本号与摘要清单在导出时固化。
 *
 * @param evidenceKey   证物业务键
 * @param versionNo     裁决封存版本号（恢复内容后导出仍标明原见证版本）
 * @param contentRef    版本内容定位引用
 * @param contentSha256 版本登记并核对通过的 SHA-256
 * @param witnesses     导出时固化的两名见证人
 */
public record ExportItemView(
        String evidenceKey,
        int versionNo,
        String contentRef,
        String contentSha256,
        List<String> witnesses) {
}
