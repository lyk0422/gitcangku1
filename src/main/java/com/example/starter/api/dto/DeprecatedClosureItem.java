package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 锁定 422 响应中的已生效弃用条目明细（按名称、版本稳定排序）。
 *
 * @param replacementVersion 同制品替代版本号
 * @param effectiveAt 弃用生效时刻（UTC，左闭）
 */
public record DeprecatedClosureItem(
        String name,
        int version,
        int replacementVersion,
        Instant effectiveAt) {
}
