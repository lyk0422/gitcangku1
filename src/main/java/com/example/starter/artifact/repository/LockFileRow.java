package com.example.starter.artifact.repository;

import java.time.LocalDateTime;

/**
 * 锁文件行记录。
 *
 * @param id           锁文件 id
 * @param requestId    创建该锁文件的幂等请求 id
 * @param rootName     根制品名称
 * @param rootVersion  根制品精确版本
 * @param repoVersion  锁定时读取的仓库版本号
 * @param createdAt    锁定时间（Asia/Shanghai）
 */
public record LockFileRow(long id, String requestId, String rootName, int rootVersion,
                          long repoVersion, LocalDateTime createdAt) {
}
