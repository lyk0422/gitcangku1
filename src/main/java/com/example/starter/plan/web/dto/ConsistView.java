package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 编组视图：编组版本、长度、停靠站台与规范化车厢集合；未登记编组时为 null。
 */
public record ConsistView(int version, int trainLength, String platformCode, String operator,
                          List<String> cars) {
}
