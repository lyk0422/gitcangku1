package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 锁定图发布结果/发布快照视图：exceptions 为发布时使用的豁免双人快照，写入后不可变。
 */
public record PublishResponse(
        long publishId,
        long lockFileId,
        Instant publishedAt,
        List<PublishExceptionView> exceptions) {
}
