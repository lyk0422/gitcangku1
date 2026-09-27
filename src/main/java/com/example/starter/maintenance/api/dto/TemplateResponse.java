package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 保养模板版本视图：检查项按规范排序 (sortOrder, itemCode) 升序。
 *
 * @param templateId      保养模板标识
 * @param templateVersion 模板版本号
 * @param items           检查项列表
 */
public record TemplateResponse(String templateId, int templateVersion, List<TemplateItemView> items) {
}
