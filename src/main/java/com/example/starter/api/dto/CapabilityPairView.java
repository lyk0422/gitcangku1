package com.example.starter.api.dto;

/**
 * 互斥能力对视图：capabilityA 字典序小于 capabilityB（无序对的规范化表示）。
 */
public record CapabilityPairView(String capabilityA, String capabilityB) {
}