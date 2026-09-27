package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id               任务ID
 * @param releaseId        所属发布单ID
 * @param deviceId         设备ID
 * @param status           任务状态
 * @param firstResult      首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param certChainVersion 拉取创建时固化的证书链版本；未纳管证书固件为 null
 * @param certFingerprint  创建时固化的证书链指纹；未纳管证书固件为 null
 * @param reviewStatus     证书撤销回执的复核状态；无需复核为 null
 * @param reviewReason     复核/拒绝原因；无复核为 null
 * @param reviewedBy       最近复核操作者；未复核为 null
 * @param reviewedAtUtc    最近复核时刻UTC；未复核为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, Integer certChainVersion, String certFingerprint,
                          CertReviewStatus reviewStatus, String reviewReason,
                          String reviewedBy, String reviewedAtUtc) {
}
