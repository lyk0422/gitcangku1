package com.example.starter.firmware.api;

import java.util.List;

/**
 * 标签字典只读视图。
 *
 * @param version 当前字典版本（实际值，从0开始）
 * @param tags    字典内全部规范化标签，字典序排序
 */
public record LabelDictView(long version, List<String> tags) {
}
