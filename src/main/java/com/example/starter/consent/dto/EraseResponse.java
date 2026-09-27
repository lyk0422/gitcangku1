package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;

/**
 * 数据清除结果。
 *
 * @param requestId  幂等请求标识
 * @param subjectKey 主体标识
 * @param purpose    用途
 * @param epoch      对象所属授权代次
 * @param recordKey  被清除对象的记录键
 * @param region     对象原驻留区域代码，清除后仍保留作为历史证据
 * @param status     ERASED 已清除
 * @param erasedAt   清除时间（UTC）
 */
public record EraseResponse(String requestId, String subjectKey, Purpose purpose, int epoch,
                            String recordKey, String region, String status, Instant erasedAt) {
}
