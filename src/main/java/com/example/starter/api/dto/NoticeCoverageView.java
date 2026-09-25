package com.example.starter.api.dto;

import java.util.List;

/**
 * 告知文本地区覆盖查询视图。
 */
public record NoticeCoverageView(
        String textKey,
        int version,
        String status,
        List<String> regions) {
}
