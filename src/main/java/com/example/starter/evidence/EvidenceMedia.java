package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物介质实体，对应 evidence_media 表。
 * sha256 登记后不可变；status 与 version 随验证/切换/取消流转。
 *
 * @param id          主键
 * @param evidenceKey 关联证物业务键
 * @param mediaNo     介质编号，同一证物内唯一
 * @param sha256      介质合成内容的 SHA-256 摘要（64 位小写十六进制），登记后不可变
 * @param status      介质状态
 * @param version     介质版本号，创建为 1，每次状态变更递增
 * @param createdBy   登记该介质的操作人
 * @param createdAt   登记时间（Asia/Shanghai）
 * @param retiredAt   作废时间（Asia/Shanghai）；null 表示未作废
 */
public record EvidenceMedia(
        Long id,
        String evidenceKey,
        String mediaNo,
        String sha256,
        MediaStatus status,
        int version,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime retiredAt) {
}
