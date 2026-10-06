package com.example.starter.firmware.domain;

/**
 * 固定设备组预备屏障发布。
 *
 * @param id                   组发布ID
 * @param model                目标设备型号，全体成员同型号
 * @param toVersion            目标固件版本，激活成功时全组统一切换
 * @param generation           当前组代次，从1开始；准备回执与激活必须匹配该代次
 * @param status               状态：PREPARING 准备中，ACTIVATED 已激活（终态），ABORTED 已中止（终态），CANCELLED 已取消（终态）
 * @param activationGeneration 激活代次，激活成功时生成且全组成员一致；未激活为 null
 * @param successCount         成功统计：激活成功时按成员数统一记成功，每设备一次
 * @param failedCount          失败统计：每台设备首次 FAILED 回执记一次，取消不计
 * @param failedDevice         触发整组中止的失败成员设备ID，未中止为 null
 */
public record GroupRelease(long id, String model, String toVersion, int generation,
                           GroupReleaseStatus status, Integer activationGeneration,
                           int successCount, int failedCount, String failedDevice) {
}
