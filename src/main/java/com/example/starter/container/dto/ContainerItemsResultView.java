package com.example.starter.container.dto;

import java.util.List;

/**
 * 容器集合变更结果视图（装载/移出）。
 *
 * @param container    变更后容器视图
 * @param evidenceKeys 本次变更涉及的证物键集合（规范化去重、字典序）
 */
public record ContainerItemsResultView(
        ContainerView container,
        List<String> evidenceKeys) {
}
