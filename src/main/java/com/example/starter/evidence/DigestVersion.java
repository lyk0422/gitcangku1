package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 数字摘要封存版本实体，对应 digest_version 表。记录只追加，版本状态只允许 ACTIVE -> SUPERSEDED。
 *
 * @param id            主键
 * @param evidenceKey   关联证物业务键
 * @param versionNo     封存版本号，同一证物从 1 递增
 * @param sealedBy      执行封存的保管人
 * @param contentRef    封存内容定位引用
 * @param contentSha256 封存时登记的内容 SHA-256（64 位小写十六进制）
 * @param status        版本状态
 * @param createdAt     封存时间（Asia/Shanghai）
 */
public record DigestVersion(
        Long id,
        String evidenceKey,
        int versionNo,
        String sealedBy,
        String contentRef,
        String contentSha256,
        DigestVersionStatus status,
        LocalDateTime createdAt) {
}
