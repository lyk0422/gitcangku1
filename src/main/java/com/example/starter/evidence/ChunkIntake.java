package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 分块接收实体，对应 chunk_intake 表。
 * 业务字段（caseKey/category/sealNo/totalLength/sha256）创建后不可修改；
 * status/version 仅在持有接收行锁（SELECT ... FOR UPDATE）的事务内变更。
 *
 * @param id          主键
 * @param intakeKey   分块接收业务键（即完成后的证物业务键），全局唯一
 * @param caseKey     所属案件键
 * @param category    证物类别
 * @param sealNo      封条编号
 * @param custodianId 创建接收记录的入库人，完成前唯一可操作人
 * @param totalLength 固定总字节数（1..65536），单位字节
 * @param sha256      完整字节流 SHA-256 十六进制摘要（小写）
 * @param status      接收状态
 * @param version     乐观版本号：当前已接收的非重复块数，每写入一个新块递增 1
 * @param createdAt   创建时间（Asia/Shanghai）
 * @param updatedAt   最近一次补块或状态变更时间（Asia/Shanghai）
 */
public record ChunkIntake(
        Long id,
        String intakeKey,
        String caseKey,
        String category,
        String sealNo,
        String custodianId,
        int totalLength,
        String sha256,
        ChunkIntakeStatus status,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
