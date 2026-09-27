package com.example.starter.firmware.domain;

/**
 * 标签字典版本元数据（单行表，id 固定为 1）。
 *
 * @param id      固定为 1
 * @param version 当前字典版本号，初始 0，每批标签修订成功后加一
 */
public record TagDictMeta(int id, long version) {
}
