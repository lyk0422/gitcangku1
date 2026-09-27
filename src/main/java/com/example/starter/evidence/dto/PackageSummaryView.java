package com.example.starter.evidence.dto;

import com.example.starter.evidence.DisclosurePackageStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 披露包冻结摘要视图：包指纹、整包摘要（逐件链摘要按序拼接的 SHA-256）与逐件链版本/摘要。
 *
 * @param packageKey    披露包业务键
 * @param caseKey       所属案件键
 * @param revision      包版本（修订号）
 * @param asOf          截至时刻（UTC）
 * @param status        包状态
 * @param fingerprint   包指纹
 * @param packageDigest 整包摘要（逐件 chainDigest 按位次拼接后的 SHA-256）
 * @param itemCount     包内证物件数
 * @param summary       生成摘要
 * @param createdAt     建包时间（UTC）
 * @param items         逐件链版本与链摘要（按规范排序）
 */
public record PackageSummaryView(
        String packageKey,
        String caseKey,
        int revision,
        LocalDateTime asOf,
        DisclosurePackageStatus status,
        String fingerprint,
        String packageDigest,
        int itemCount,
        String summary,
        LocalDateTime createdAt,
        List<PackageItemView> items) {
}
