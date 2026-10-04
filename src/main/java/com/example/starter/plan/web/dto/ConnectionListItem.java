package com.example.starter.plan.web.dto;

/**
 * 计划接续列表项：role 为查询计划在接续中的角色（PREDECESSOR 前车 / SUCCESSOR 后车）。
 */
public record ConnectionListItem(String role, ConnectionView connection) {
}