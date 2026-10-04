package com.example.starter.repo;

/**
 * 运动间隔许可持久化记录（轨迹快照不可变）。
 *
 * @param permitId        许可唯一标识（不可变）
 * @param routeId         引用的航线标识
 * @param routeVersion    许可时明确引用的航线版本；航线后续替换不影响本许可轨迹
 * @param airspaceVersion 许可审查通过时的全局空域版本
 * @param startEpochSec   有效起点（含），UTC epoch 整秒
 * @param endEpochSec     有效终点（不含），UTC epoch 整秒，大于起点
 * @param startX          起点 X 坐标快照（米，最多 3 位小数，当前来源于整数航点）
 * @param startY          起点 Y 坐标快照（米）
 * @param endX            终点 X 坐标快照（米）
 * @param endY            终点 Y 坐标快照（米）
 * @param status          ACTIVE / REVOKED
 * @param revokedEpochSec 撤销生效时刻（UTC epoch 秒）；null 表示未撤销；
 *                        撤销后仅 [startEpochSec, revokedEpochSec) 的历史轨迹仍计入占用
 * @param requestId       创建许可的写操作请求标识
 * @param createdAt       创建时间（epoch 毫秒，UTC）
 */
public record PermitPo(String permitId, String routeId, int routeVersion, long airspaceVersion,
                       long startEpochSec, long endEpochSec,
                       double startX, double startY, double endX, double endY,
                       String status, Long revokedEpochSec,
                       String requestId, long createdAt) {

    /**
     * 计入占用的有效终点（不含）：未撤销为 endEpochSec；
     * 已撤销为撤销时刻与终点中较小者（撤销释放未来占用，历史轨迹保留）。
     */
    public long effectiveEndEpochSec() {
        if (revokedEpochSec == null) {
            return endEpochSec;
        }
        return Math.min(endEpochSec, revokedEpochSec);
    }

    /** 是否仍有计入占用的区间（左闭右开非空）。 */
    public boolean hasOccupancy() {
        return startEpochSec < effectiveEndEpochSec();
    }
}