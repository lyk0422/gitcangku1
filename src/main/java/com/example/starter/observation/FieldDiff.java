package com.example.starter.observation;

/**
 * 单字段更正差异：提交前有效原值与更正值。
 *
 * @param from 提交前该字段的有效原值（已应用此前有效附页后的值）
 * @param to   本附页的更正值
 */
public record FieldDiff(String from, String to) {
}
