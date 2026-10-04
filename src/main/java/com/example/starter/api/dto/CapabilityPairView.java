package com.example.starter.api.dto;

/**
 * 一对互斥能力的视图：capabilityA 字典序不大于 capabilityB（无序对的规范化表示）。
 */
public record CapabilityPairView(String capabilityA, String capabilityB) {
}