package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 阅检副本实体，对应 inspection_copy 表。
 * 签发时冻结摘要、水印、合成文本与 UTC 有效区间（左闭右开，最长一小时），之后不可修改；
 * 回收仅置状态，签发历史保留。
 *
 * @param id            主键
 * @param copyId        不可重复副本ID，全局唯一，水印组成部分
 * @param evidenceKey   关联证物业务键
 * @param reviewerId    阅检人，仅本人可在有效期内读取
 * @param digest        签发时冻结的原件数字摘要
 * @param watermark     签发时冻结的水印，包含副本ID与阅检人
 * @param syntheticText 签发时冻结的合成文本
 * @param validFrom     UTC 有效期起点（左闭，含该时刻）
 * @param validTo       UTC 有效期终点（右开，不含该时刻）
 * @param status        副本状态
 * @param issuedAt      签发时刻（UTC）
 * @param revokedAt     回收时刻（UTC）；null 表示未回收
 * @param createdAt     记录创建时间（Asia/Shanghai）
 */
public record InspectionCopy(
        Long id,
        String copyId,
        String evidenceKey,
        String reviewerId,
        String digest,
        String watermark,
        String syntheticText,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        InspectionCopyStatus status,
        LocalDateTime issuedAt,
        LocalDateTime revokedAt,
        LocalDateTime createdAt) {
}