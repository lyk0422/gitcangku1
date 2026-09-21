package com.example.starter.evidence.domain;

import java.time.LocalDateTime;

/**
 * 幂等命令日志行。httpStatus/responseBody 在命令提交完成前为 null。
 */
public record CommandLog(
        Long id,
        String commandKey,
        CommandType commandType,
        String actorId,
        String fingerprint,
        Integer httpStatus,
        String responseBody,
        LocalDateTime createdAt
) {
}
