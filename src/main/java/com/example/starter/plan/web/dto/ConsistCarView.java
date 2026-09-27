package com.example.starter.plan.web.dto;

/**
 * 编组车厢视图。hazardClass 为 JSON null 表示普通车厢，null 语义稳定不省略。
 */
public record ConsistCarView(int position, String hazardClass) {
}
