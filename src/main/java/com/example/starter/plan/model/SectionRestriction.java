package com.example.starter.plan.model;

import java.util.List;

/**
 * 限制区段危险品开放清单的一个版本。
 *
 * @param sectionId       区段 ID
 * @param version         区段配置版本号，从 1 开始；未配置时上层以版本 0 + null 清单表示不限制
 * @param allowedClasses  开放危险品类别清单（规范化、字母序去重）；空列表表示该区段不允许任何危险品
 * @param operator        提交操作者
 * @param createdAt       版本创建时刻，UTC 毫秒
 */
public record SectionRestriction(String sectionId, int version, List<String> allowedClasses,
                                 String operator, long createdAt) {
}
