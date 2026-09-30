package com.example.starter.po;

import java.time.LocalDateTime;

/**
 * 潮高版本持久化对象。
 */
public record TideVersionPo(
        Long id,
        long versionNo,
        LocalDateTime rangeStart,
        LocalDateTime rangeEnd,
        String status,
        LocalDateTime publishedAt) {
}
