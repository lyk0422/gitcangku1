package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 不确定度预算登记/修订请求。以“完整替换”语义产生新预算版本：
 * 本次分量列表即修订后的完整预算，未列出的旧分量一律删除（旧版本仍保留在历史中）。
 *
 * @param components      完整分量集合，1～100 个；名称预算内唯一，值非负
 * @param expectedVersion 客户端持有的测量修订版本号；为空表示不做乐观版本校验
 */
public record SaveBudgetRequest(List<ComponentRequest> components, Long expectedVersion) {
}
