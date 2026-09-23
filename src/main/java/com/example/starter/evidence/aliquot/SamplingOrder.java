package com.example.starter.evidence.aliquot;

import java.time.LocalDateTime;

/**
 * 联合取样单实体，对应 sampling_order 表。一单从 2~20 件不同母样各取正整数数量。
 *
 * @param id                 主键
 * @param requestId          联合取样申请业务键，全局唯一
 * @param aliquotKey         申请指定的唯一子样业务键
 * @param custodianId        申请时各母样的当前保管人（申请操作人，须一致）
 * @param status             申请单状态
 * @param version            申请单版本号，每次审核确认加 1；二次确认必须携带当前版本
 * @param requestFingerprint 申请操作人、aliquotKey 与母样键-数量集合排序规范化后的 SHA-256
 * @param commandKey         首次申请使用的幂等命令键，换序重放据此取回首次响应
 * @param createdAt          申请时间（Asia/Shanghai）
 * @param confirmedAt        二次确认完成时间；NULL 表示未完成
 * @param decidedAt          拒绝或取消时间；NULL 表示未终态
 */
public record SamplingOrder(
        Long id,
        String requestId,
        String aliquotKey,
        String custodianId,
        SamplingStatus status,
        long version,
        String requestFingerprint,
        String commandKey,
        LocalDateTime createdAt,
        LocalDateTime confirmedAt,
        LocalDateTime decidedAt) {
}
