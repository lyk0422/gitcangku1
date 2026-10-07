package com.example.starter.evidence.dto;

import com.example.starter.evidence.MediaStatus;

import java.time.LocalDateTime;

/**
 * 介质视图。
 *
 * @param evidenceKey 关联证物业务键
 * @param mediaNo     介质编号
 * @param sha256      介质内容 SHA-256
 * @param status      介质状态（ACTIVE/UNVERIFIED/VERIFIED/RETIRED）
 * @param createdAt   登记时间（Asia/Shanghai）
 * @param updatedAt   最近一次状态变更时间（Asia/Shanghai）
 */
public record MediaView(
        String evidenceKey,
        String mediaNo,
        String sha256,
        MediaStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
