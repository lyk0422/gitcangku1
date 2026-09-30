package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 数字摘要见证实体，对应 digest_witness 表。记录只追加：
 * 一名保管人对某证物某一封存版本的确认；版本失效后该见证不会转移到新版本。
 *
 * @param id          主键
 * @param evidenceKey 关联证物业务键
 * @param versionNo   被见证的封存版本号
 * @param witnessId   见证保管人标识，须为该证物历任或现任保管人，同版本两人不得重复
 * @param createdAt   见证提交时间（Asia/Shanghai）
 */
public record DigestWitness(
        Long id,
        String evidenceKey,
        int versionNo,
        String witnessId,
        LocalDateTime createdAt) {
}
