package com.example.starter.firmware.domain;

/**
 * 任务暂存空间预留，仅活跃（PENDING）任务持有，任务首次终结或发布取消时整行删除。
 *
 * @param id             预留ID
 * @param taskId         关联任务ID，一对一
 * @param releaseId      所属发布单ID
 * @param deviceId       设备ID，同设备至多一项活跃暂存
 * @param oldImageBytes  领取时旧镜像字节数快照
 * @param packageBytes   压缩包字节数快照
 * @param newImageBytes  解包后新镜像字节数快照
 * @param peakBytes      领取时峰值字节数=旧镜像+压缩包+新镜像
 */
public record StagingReservation(long id, long taskId, long releaseId, String deviceId,
                                 long oldImageBytes, long packageBytes, long newImageBytes,
                                 long peakBytes) {

    /**
     * 活跃预留占用字节数（压缩包+新镜像），用于计算当前余量。
     */
    public long heldBytes() {
        return packageBytes + newImageBytes;
    }
}
