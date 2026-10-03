package com.example.starter.domain;

import java.time.Instant;

/**
 * 幂等请求日志，仅成功请求占键。
 *
 * @param requestId 幂等请求键，全局唯一
 * @param requestType 请求类型：ENROLL/READING/HANDOVER
 * @param requestHash 请求参数指纹，同键异参判 409
 * @param responseJson 首次成功响应快照，用于同参重放
 * @param createdAt 请求受理时刻，UTC
 */
public record RequestLogRecord(String requestId, String requestType, String requestHash,
                               String responseJson, Instant createdAt) {
}