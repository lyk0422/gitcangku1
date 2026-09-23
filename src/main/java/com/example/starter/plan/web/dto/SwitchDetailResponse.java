package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 切换单详情 / 激活结果：封锁信息、全部一对一映射及各映射的前后占用。
 * REGISTERED 状态 mappings 为空；ACTIVE 状态为不可变快照内容。
 */
public record SwitchDetailResponse(SwitchView switchInfo, List<SwitchMappingView> mappings) {
}
