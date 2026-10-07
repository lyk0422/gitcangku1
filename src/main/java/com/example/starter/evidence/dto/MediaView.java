package com.example.starter.evidence.dto;

import com.example.starter.evidence.MediaStatus;

import java.time.LocalDateTime;

/**
 * 介质视图。
 *
 * @param evidenceKey 关联证物业务键
 * @param mediaNo     介质编号
 * @param sha256      介质合成内容的 SHA-256 摘要
 * @param status      介质状态
 * @param version     介质版本号
 * @param createdBy   登记操作人
 * @param createdAt   登记时间（Asia/Shanghai）
 * @param retiredAt   作废时间（Asia/Shanghai）；null 表示未作废
 */
public record MediaView(
        String evidenceKey,
        String mediaNo,
        String sha256,
        MediaStatus status,
        int version,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime retiredAt) {
}
