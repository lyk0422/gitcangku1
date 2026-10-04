package com.example.starter.cue;

import java.time.LocalDateTime;

/**
 * 提示点编排版本。
 *
 * @param id 版本ID
 * @param status 版本状态：DRAFT草稿/FROZEN已冻结
 * @param createdAt 创建时间，服务器本地时区
 */
public record CueVersion(long id, VersionStatus status, LocalDateTime createdAt) {
}