package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 目标标签表达式（规范化后）。
 *
 * @param include 必须包含的标签集合（规范化排序），设备标签需全部包含
 * @param exclude 不得包含的标签集合（规范化排序），设备标签需一个都不包含
 * @param anyOf   至少包含一项的标签集合（规范化排序），非空时设备标签需与其有交集
 */
public record TagExpression(List<String> include, List<String> exclude, List<String> anyOf) {

    public static final String INCLUDE = "include";
    public static final String EXCLUDE = "exclude";
    public static final String ANY_OF = "anyOf";
}
