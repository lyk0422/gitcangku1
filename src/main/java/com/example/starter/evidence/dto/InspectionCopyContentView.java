package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 阅检副本内容视图：仅阅检人本人在有效期内读取时返回，每次读取重新鉴权。
 * 只包含本副本的冻结元数据与原件合成文本，不包含其他副本或原件内部记录。
 *
 * @param copyKey       副本业务键（不可重复副本 ID）
 * @param evidenceKey   关联证物业务键
 * @param reviewerId    阅检人
 * @param digest        签发时冻结的原件数字摘要
 * @param watermark     签发时冻结的水印，含副本 ID 与阅检人
 * @param validFrom     UTC 生效时刻（含）
 * @param validUntil    UTC 失效时刻（不含）
 * @param syntheticText 原件合成文本元数据
 */
public record InspectionCopyContentView(
        String copyKey,
        String evidenceKey,
        String reviewerId,
        String digest,
        String watermark,
        LocalDateTime validFrom,
        LocalDateTime validUntil,
        String syntheticText) {
}