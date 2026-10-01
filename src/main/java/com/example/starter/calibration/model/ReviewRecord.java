package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 离群复核记录（表 review_record）。每次复核（含 STALE）不可变追加；
 * 仅 VALID 记录计入裁决法定人数（两名不同复核角色结论一致才形成有效裁决）。
 *
 * @param id                  复核记录 ID（自增）
 * @param outlierId           离群记录 ID
 * @param reviewer            复核人（须不同于读数提交人）
 * @param decision            复核结论：ACCEPT 保留读数 / REJECT 排除读数
 * @param reason              复核理由；ACCEPT 必填，REJECT 可选；未填写为 null
 * @param measurementRevision 复核引用的测量修订版本
 * @param certificateId       复核引用的证书版本（证书 ID）
 * @param status              状态：VALID 有效 / STALE 版本已变更或证书失效
 * @param createdAt           复核时间（UTC）
 */
public record ReviewRecord(
        long id,
        long outlierId,
        String reviewer,
        ReviewDecision decision,
        String reason,
        int measurementRevision,
        long certificateId,
        ReviewStatus status,
        Instant createdAt) {
}