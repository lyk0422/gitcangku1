package com.example.starter.evidence;

/**
 * 导出包清单条目实体，对应 export_package_item 表。建包时每件证物所处版本与摘要的快照，
 * 只追加、不可变；后续证物内容变化不影响已导出清单。
 *
 * @param id            主键
 * @param packageId     关联导出包主键
 * @param evidenceKey   包内证物业务键
 * @param versionNo     建包裁决时该证物所处的封存版本号
 * @param contentSha256 建包时该证物封存版本登记的 SHA-256 摘要快照
 */
public record ExportPackageItem(
        Long id,
        Long packageId,
        String evidenceKey,
        int versionNo,
        String contentSha256) {
}
