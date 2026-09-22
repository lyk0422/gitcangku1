package com.example.starter.artifact.dto;

import java.util.List;

/**
 * 锁定成功响应 / 锁文件详情。
 *
 * @param lockFileId        锁文件 id
 * @param rootName          根制品名称
 * @param rootVersion       根制品精确版本
 * @param repositoryVersion 锁定时读取的仓库版本号
 * @param entries           精确依赖集合（含根），按名称升序
 */
public record LockFileResponse(long lockFileId, String rootName, int rootVersion,
                               long repositoryVersion, List<LockEntryDto> entries) {
}
