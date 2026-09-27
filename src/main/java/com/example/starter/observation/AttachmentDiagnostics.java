package com.example.starter.observation;

/**
 * 附件诊断信息：数量类指标只返回实际值，便于调用方直接核对。
 *
 * @param attachmentId            附件唯一标识
 * @param observationId           所属观测记录标识
 * @param totalVersions           脱敏版本总数（含已撤销）
 * @param effectiveVersions       当前有效（未撤销）版本数
 * @param revokedVersions         已撤销版本数
 * @param latestVersion           最新脱敏版本号，无脱敏时为 0
 * @param latestEffectiveVersion  最新有效版本号，无有效版本时为 null
 */
public record AttachmentDiagnostics(
        String attachmentId,
        String observationId,
        int totalVersions,
        int effectiveVersions,
        int revokedVersions,
        int latestVersion,
        Integer latestEffectiveVersion) {
}
