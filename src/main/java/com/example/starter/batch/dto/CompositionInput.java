package com.example.starter.batch.dto;

import com.example.starter.batch.SegregationLevel;

import java.util.List;

/**
 * 成分输入：过敏原代码集合 + 隔离级别。
 * 代码集合换序视为同参：服务端统一去空白、去重并按字典序规范化；
 * 未知代码返回 422；segregationLevel 缺失（空级别）由业务层返回 422 而非 400。
 */
public record CompositionInput(
        List<String> allergenCodes,
        SegregationLevel segregationLevel
) {
}
