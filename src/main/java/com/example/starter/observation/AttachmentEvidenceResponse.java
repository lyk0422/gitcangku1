package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 证据视图：附件原始摘要（清除后为 null）与其全部脱敏版本，仅具备证据权限的查询可返回。
 *
 * @param attachmentId    附件唯一标识
 * @param observationId   所属观测记录标识
 * @param fileName        附件文件名
 * @param sizeBytes       附件内容大小（字节）
 * @param originalSummary 原始内容摘要；物理清除后为 null
 * @param contentCleared  原始内容是否已被物理清除
 * @param clearedAtUtc    物理清除时刻（UTC）；未清除为 null
 * @param redactionVersions 全部脱敏版本（含已撤销），按版本号升序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentEvidenceResponse(
        String attachmentId,
        String observationId,
        String fileName,
        long sizeBytes,
        String originalSummary,
        boolean contentCleared,
        Instant clearedAtUtc,
        List<RedactionVersionResponse> redactionVersions) {

    /**
     * 由附件元数据与全部脱敏版本构造证据视图。
     */
    public static AttachmentEvidenceResponse of(AttachmentMeta attachment, List<RedactionVersion> versions) {
        return new AttachmentEvidenceResponse(
                attachment.attachmentId(), attachment.observationId(), attachment.fileName(),
                attachment.sizeBytes(), attachment.originalSummary(), attachment.contentCleared(),
                attachment.clearedAtUtc(),
                versions.stream().map(RedactionVersionResponse::evidenceView).toList());
    }
}
