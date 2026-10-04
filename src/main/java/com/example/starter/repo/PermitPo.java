package com.example.starter.repo;

/**
 * 两点匀速航线运动间隔许可持久化记录。
 * 轨迹端点以毫米（米×1000）整数保存，精确保留最多 3 位小数，不提前舍入。
 *
 * @param permitId     许可唯一标识（不可变）
 * @param routeId      引用的两点航线标识
 * @param routeVersion 申请时引用并快照的航线版本；航线后续更新不影响本许可轨迹
 * @param startSecond  有效起点（含），UTC epoch 整秒
 * @param endSecond    有效终点（不含），UTC epoch 整秒
 * @param axMilli      起点 X 快照，单位毫米
 * @param ayMilli      起点 Y 快照，单位毫米
 * @param bxMilli      终点 X 快照，单位毫米
 * @param byMilli      终点 Y 快照，单位毫米
 * @param status       ACTIVE 有效参与间隔校验；REVOKED 已撤销，释放未来占用、轨迹保留
 * @param requestId    创建许可的写操作请求标识
 * @param createdAt    创建时间，epoch 毫秒（UTC）
 * @param revokedAt    撤销时间，epoch 毫秒（UTC）；null 表示仍有效
 */
public record PermitPo(String permitId, String routeId, int routeVersion,
                       long startSecond, long endSecond,
                       long axMilli, long ayMilli, long bxMilli, long byMilli,
                       String status, String requestId, long createdAt, Long revokedAt) {
}