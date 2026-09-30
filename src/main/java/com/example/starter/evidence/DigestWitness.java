package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 摘要见证记录实体，对应 digest_witness 表。只追加，不可变。
 *
 * @param id          主键
 * @param evidenceKey 关联证物业务键
 * @param versionNo   被见证的封存版本号
 * @param witnessId   见证保管人
 * @param commandKey  见证命令幂等键
 * @param createdAt   见证时间（Asia/Shanghai）
 */
public record DigestWitness(
        Long id,
        String evidenceKey,
        int versionNo,
        String witnessId,
        String commandKey,
        LocalDateTime createdAt) {
}
