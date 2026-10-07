package com.example.starter.api.dto;

/**
 * 响应中的 peer 依赖区间视图。
 */
public record PeerView(String name, int minimumVersion, int maximumVersion) {
}
