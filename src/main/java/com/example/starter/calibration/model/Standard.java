package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 标准器。一个标准器可有多个标准器版本，版本之间构成血缘树。
 *
 * @param id         标准器 ID（自增）
 * @param standardId 标准器业务 ID，全局唯一；血缘路径等长时按其字典序择路
 * @param name       标准器名称
 * @param createdAt  创建时间（UTC）
 */
public record Standard(
        long id,
        String standardId,
        String name,
        Instant createdAt) {
}
