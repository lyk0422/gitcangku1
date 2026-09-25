package com.example.starter.api.dto;

import java.util.List;

/**
 * 来源违规视图：可区分原因及从根到违规坐标的完整路径。
 */
public record ViolationView(String reason, List<String> path, String detail) {
}
