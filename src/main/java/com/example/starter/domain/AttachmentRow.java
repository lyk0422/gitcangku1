package com.example.starter.domain;

import java.time.Instant;

/**
 * 附着记录：标签-动物-站点的有效期关联，左闭右开。
 * startAt 为有效期起（UTC，含该时刻）；endAt 为有效期止（UTC，不含该时刻），null 表示仍附着。
 * 摘除只写 endAt，历史行不被覆盖。
 */
public record AttachmentRow(
        Long id,
        String tagCode,
        String animalCode,
        String stationCode,
        Instant startAt,
        Instant endAt) {
}