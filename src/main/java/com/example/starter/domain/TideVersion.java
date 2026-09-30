package com.example.starter.domain;

import java.time.LocalDateTime;

/**
 * 潮高版本（一次潮汐表修订发布）。
 *
 * @param id          主键
 * @param versionNo   版本号，从 1 递增，唯一
 * @param publishedAt 发布时间，业务时区 Asia/Shanghai
 * @param remark      备注，可为空
 */
public record TideVersion(Long id, Integer versionNo, LocalDateTime publishedAt, String remark) {
}
