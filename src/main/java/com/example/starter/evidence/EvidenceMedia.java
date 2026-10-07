package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 证物存储介质实体，对应 evidence_media 表。
 * 介质编号与摘要入库后不可修改，仅状态随迁移流转；RETIRED 介质保留历史元数据。
 *
 * @param id          主键
 * @param evidenceKey 关联证物业务键
 * @param mediaNo     介质编号，证物内唯一
 * @param sha256      介质内容 SHA-256（小写十六进制，64 字符）；验证通过后为已确认摘要
 * @param status      介质状态
 * @param createdAt   登记时间（Asia/Shanghai）
 * @param updatedAt   最近一次状态变更时间（Asia/Shanghai）
 */
public record EvidenceMedia(
        Long id,
        String evidenceKey,
        String mediaNo,
        String sha256,
        MediaStatus status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
