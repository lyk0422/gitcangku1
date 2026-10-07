package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 分块入库待接收记录，对应 chunk_intake 表。
 * 创建时固定总字节数与完整 SHA-256；仅入库人可补块、完成或放弃。
 *
 * @param id          主键
 * @param intakeKey   分块入库业务键，全局唯一
 * @param evidenceKey 完成接收后写入的证物业务键；放弃后可由新记录复用
 * @param caseKey     所属案件键
 * @param category    证物类别
 * @param sealNo      封条编号
 * @param totalBytes  声明的总字节数，1至65536，创建后不可变
 * @param sha256      完整内容的 SHA-256 摘要（64位十六进制小写）
 * @param creatorId   创建待接收记录的入库人
 * @param status      接收状态
 * @param version     记录版本，从 0 开始，每次补块、完成或放弃加 1
 * @param createdAt   创建时间（Asia/Shanghai）
 * @param decidedAt   完成或放弃时间（Asia/Shanghai）；NULL 表示仍在接收中
 */
public record ChunkIntake(
        Long id,
        String intakeKey,
        String evidenceKey,
        String caseKey,
        String category,
        String sealNo,
        int totalBytes,
        String sha256,
        String creatorId,
        ChunkIntakeStatus status,
        int version,
        LocalDateTime createdAt,
        LocalDateTime decidedAt) {
}
