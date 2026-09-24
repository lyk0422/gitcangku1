package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import com.example.starter.consent.SnapshotStatus;

/**
 * 快照中单个用途的固化视图：生成时刻的有效代次、记录数与按 recordKey 升序的完整记录。
 *
 * @param purpose     用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch       生成时刻该用途的有效授权代次
 * @param recordCount 生成时刻该用途该代次的记录数
 * @param status      读取时状态：CURRENT 与当前有效代次一致 / STALE 已撤回或已推进
 * @param records     按 recordKey 升序固化的完整记录
 */
public record SnapshotPurposeView(
        Purpose purpose,
        int epoch,
        int recordCount,
        SnapshotStatus status,
        List<SnapshotRecordItem> records) {
}
