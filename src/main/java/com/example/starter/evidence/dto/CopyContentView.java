package com.example.starter.evidence.dto;

import com.example.starter.evidence.InspectionCopyStatus;

import java.time.LocalDateTime;

/**
 * 阅检副本内容视图（元数据 + 冻结的合成文本）。
 * 仅签发首次响应与有效期内本人读取时返回；不包含原件其他内部记录。
 *
 * @param copyId        不可重复副本ID
 * @param evidenceKey   关联证物业务键
 * @param reviewerId    阅检人
 * @param digest        签发时冻结的原件数字摘要
 * @param watermark     签发时冻结的水印，包含副本ID与阅检人
 * @param validFrom     UTC 有效期起点（左闭）
 * @param validTo       UTC 有效期终点（右开）
 * @param status        副本状态
 * @param syntheticText 签发时冻结的合成文本
 */
public record CopyContentView(
        String copyId,
        String evidenceKey,
        String reviewerId,
        String digest,
        String watermark,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        InspectionCopyStatus status,
        String syntheticText) {
}