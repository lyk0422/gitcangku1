package com.example.starter.film.model;

import java.time.LocalDateTime;

/**
 * 片段修复工作版（只追加，永不修改旧记录）。每次提交产生一条新版次行。
 *
 * @param id         行ID
 * @param filmId     所属影片ID
 * @param seq        片段序号，从 1 开始
 * @param versionNo  版次，从 1 开始递增；0 表示“尚无版本”，仅用于提交参数
 * @param digest     内容摘要，4-128 位字母/数字及 {@code . _ : -}
 * @param requestId  提交请求ID（幂等键），全局唯一
 * @param createdAt  提交时间，Asia/Shanghai
 */
public record SegmentVersion(
        Long id,
        Long filmId,
        int seq,
        int versionNo,
        String digest,
        String requestId,
        LocalDateTime createdAt
) {
}
