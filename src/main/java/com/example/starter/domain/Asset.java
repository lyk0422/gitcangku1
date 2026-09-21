package com.example.starter.domain;

/**
 * 素材。
 *
 * @param id         稳定素材 ID，创建后不变
 * @param durationMs 素材时长，单位毫秒，正整数
 */
public record Asset(String id, long durationMs) {
}
