package com.example.starter.water;

import java.time.Instant;

/**
 * 幂等命令日志实体：记录某 commandKey 首次成功执行的操作、参数指纹与响应快照。
 */
public record CommandRecord(
        String commandKey,
        String operation,
        String paramsHash,
        int httpStatus,
        String responseBody,
        Instant createdAt) {
}
