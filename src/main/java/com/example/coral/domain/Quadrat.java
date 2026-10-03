package com.example.coral.domain;

import java.time.Instant;

/**
 * 珊瑚移植样方。
 *
 * @param id 样方主键
 * @param quadratCode 样方编号，项目内唯一
 * @param name 样方名称，可空
 * @param status 样方状态：OPEN开放/SEALED已封存
 * @param createdAt 创建时刻（UTC）
 * @param sealedAt 封存时刻（UTC），未封存为null
 */
public record Quadrat(long id, String quadratCode, String name, QuadratStatus status,
        Instant createdAt, Instant sealedAt) {
}