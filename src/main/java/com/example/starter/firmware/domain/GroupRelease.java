package com.example.starter.firmware.domain;

/**
 * 固定设备组预备屏障发布单。
 *
 * @param id                   组发布单ID
 * @param version              组发布单版本号，从1开始，激活或取消成功加一
 * @param model                目标设备型号，全体成员同型号
 * @param toVersion            目标固件版本，激活时全组统一切换
 * @param status               状态：PREPARING 准备中，ACTIVATED 已激活（终态），
 *                             ABORTED 准备失败中止（终态），CANCELLED 已取消（终态）
 * @param prepareGeneration    当前准备代次，从1开始；PREPARE 回执必须匹配该代次
 * @param activationGeneration 组激活代次，激活成功时原子生成，未激活为 null
 * @param failedDeviceId       导致整组 ABORTED 的失败成员设备ID，未失败为 null
 * @param activatedAtUtc       激活时刻，UTC，ISO-8601 格式，未激活为 null
 */
public record GroupRelease(long id, int version, String model, String toVersion,
                           GroupReleaseStatus status, int prepareGeneration,
                           Integer activationGeneration, String failedDeviceId,
                           String activatedAtUtc) {
}
