package com.example.starter.firmware.domain;

/**
 * 固件灰度发布单。
 *
 * @param id                      发布单ID
 * @param version                 发布单版本号，从1开始，每次扩量或人工恢复成功加一
 * @param model                   目标设备型号
 * @param fromVersion             来源固件版本
 * @param toVersion               目标固件版本，必须与来源版本不同
 * @param ratio                   投放比例，取值0~100，只增不减
 * @param status                  状态：ACTIVE 投放中，PAUSED 失败率自动暂停，CANCELLED 已取消（终态）
 * @param sampleFloor             失败率统计样本下限，取值2~100
 * @param failureThresholdPercent 失败率阈值百分比，取值1~100
 * @param monitorRound            当前监控轮次，从1开始，人工恢复后加一
 * @param roundSuccess            当前监控轮次内首次进入 SUCCESS 的任务数
 * @param roundFailed             当前监控轮次内首次进入 FAILED 的任务数
 * @param chunkCount              分片总数；null 表示未登记分片清单（旧式发布单，回执不做完整性门禁）
 * @param packageDigest           完整包聚合摘要（SHA-256，64位小写十六进制）；未登记分片清单时为 null
 */
public record ReleaseOrder(long id, int version, String model, String fromVersion, String toVersion,
                           int ratio, ReleaseStatus status, int sampleFloor, int failureThresholdPercent,
                           int monitorRound, int roundSuccess, int roundFailed,
                           Integer chunkCount, String packageDigest) {

    /**
     * 是否已登记分片清单（任务需通过完整性核验才可安装）。
     */
    public boolean hasManifest() {
        return chunkCount != null;
    }
}
