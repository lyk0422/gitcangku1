package com.example.starter.plan.web.dto;

/**
 * 危险品联合校验单项结论，检查项按固定顺序返回：
 * CAR_POSITION_UNIQUE → SEPARATION → SECTION_CATEGORY_AUTH。
 */
public record HazardCheckView(String check, String result) {
}
