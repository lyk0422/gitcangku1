package com.example.starter.container.dto;

/**
 * 借出/迁移阻断原因视图。证物处于 INSPECTION_FAILED 容器时返回 409 并附带原因。
 *
 * @param evidenceKey  证物业务键
 * @param containerId  所属容器业务键
 * @param blocked      是否阻断
 * @param reasonCode   阻断原因码：CONTAINER_INSPECTION_FAILED
 * @param reason       阻断原因描述
 */
public record BlockReasonView(
        String evidenceKey,
        String containerId,
        boolean blocked,
        String reasonCode,
        String reason) {
}
