package com.example.starter.archival.domain;

import java.time.LocalDateTime;

/**
 * 幂等请求记录。requestId 为主键，同参重放首次结果，异参冲突（409）。
 *
 * @param requestId   幂等键
 * @param operation   操作类型：ENROLL 入组；SEAL 封存
 * @param fingerprint 首次请求参数指纹
 * @param resultJson  首次处理结果快照（JSON）
 * @param batchId     关联批次ID，可能为空
 * @param createdAt   首次请求时间（Asia/Shanghai）
 */
public record IdempotentRequest(String requestId, String operation, String fingerprint,
                                String resultJson, Long batchId, LocalDateTime createdAt) {
}
