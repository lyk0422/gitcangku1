package com.example.starter.calibration.model;

/**
 * 冻结血缘路径节点：激活时为每条受影响测量结果记录其到失效根的最短血缘路径，
 * 路径等长时按 standardId 字典序选择。
 *
 * @param impactVersion    影响版本号（与失效单一一对应）
 * @param measurementId    受影响测量记录 ID
 * @param depth            该节点在路径上的深度：0=测量直接绑定版本，向失效根递增
 * @param standardVersionId 路径上的标准器版本 ID
 */
public record ImpactPathNode(
        String impactVersion,
        long measurementId,
        int depth,
        long standardVersionId) {
}
