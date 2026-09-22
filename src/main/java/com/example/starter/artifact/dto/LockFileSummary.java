package com.example.starter.artifact.dto;

import java.time.LocalDateTime;

/**
 * 锁文件历史摘要。
 *
 * @param lockFileId        锁文件 id
 * @param rootName          根制品名称
 * @param rootVersion       根制品精确版本
 * @param repositoryVersion 锁定时读取的仓库版本号
 * @param createdAt         锁定时间（Asia/Shanghai）
 */
public record LockFileSummary(long lockFileId, String rootName, int rootVersion,
                              long repositoryVersion, LocalDateTime createdAt) {
}
