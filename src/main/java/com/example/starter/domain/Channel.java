package com.example.starter.domain;

/**
 * 频道。
 *
 * @param id               频道 ID
 * @param fallbackAssetId  保底素材 ID；空档、无编排或授权失效时播出，不会被撤销
 */
public record Channel(String id, String fallbackAssetId) {
}
