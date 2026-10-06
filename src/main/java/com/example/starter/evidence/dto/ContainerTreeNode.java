package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 容器树节点视图：箱信息 + 直接子箱（递归）+ 直接证物成员。
 * 待交接期间树被冻结，视图仍完整列出全部成员。
 *
 * @param container       当前箱视图
 * @param childContainers 直接子箱节点
 * @param evidenceItems   直接证物成员视图
 */
public record ContainerTreeNode(
        ContainerView container,
        List<ContainerTreeNode> childContainers,
        List<EvidenceView> evidenceItems) {
}
