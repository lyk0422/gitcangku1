package com.example.starter.observation;

import java.util.List;

/**
 * 脱敏能力诊断响应：全部为实际统计值，按附件逐项给出版本数量差额，调用方无需猜测。
 *
 * @param observationId          观测记录唯一标识
 * @param attachmentCount        附件总数（实际值）
 * @param purgedAttachmentCount  已物理清除附件数（实际值）
 * @param redactionVersionCount  脱敏版本总数（实际值）
 * @param activeVersionCount     有效（ACTIVE）脱敏版本数（实际值）
 * @param revokedVersionCount    已撤销（REVOKED）脱敏版本数（实际值）
 * @param items                  逐附件诊断明细，按附件标识升序
 */
public record RedactionDiagnosticsResponse(
        String observationId,
        int attachmentCount,
        int purgedAttachmentCount,
        int redactionVersionCount,
        int activeVersionCount,
        int revokedVersionCount,
        List<Item> items) {

    /**
     * 单附件诊断明细。
     *
     * @param attachmentId         附件唯一标识
     * @param purged               是否已物理清除
     * @param originalAvailable    原始摘要是否仍可读（未清除）
     * @param totalVersions        该附件脱敏版本总数（实际值）
     * @param activeVersionCount   有效版本数（实际值）
     * @param revokedVersionCount  已撤销版本数（实际值；total - active - revoked 恒为 0）
     * @param latestActiveVersion  最新有效版本号；无有效版本时为 null
     */
    public record Item(
            String attachmentId,
            boolean purged,
            boolean originalAvailable,
            int totalVersions,
            int activeVersionCount,
            int revokedVersionCount,
            Integer latestActiveVersion) {
    }
}
