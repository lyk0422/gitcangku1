package com.example.starter.firmware.domain;

/**
 * 正式投放前门禁结论。结论按型号+目标版本唯一且不可变；新的发布版本（新 target_version）必须重新演练。
 *
 * @param id                   门禁记录ID
 * @param model                发布设备型号
 * @param targetVersion        发布目标固件版本（正式投放的 to_version）
 * @param status               门禁结论：READY_FOR_RELEASE
 * @param rehearsalId          授予该门禁的成功演练ID（业务证据，不可变）
 * @param requiredDeviceCount  要求成功的设备数（演练规范集合数量）
 * @param actualSuccessCount   实际成功回退设备数，授予时等于要求数
 * @param grantedAtUtc         门禁授予时刻，UTC，ISO-8601
 */
public record ReleaseGate(long id, String model, String targetVersion, String status, long rehearsalId,
                          int requiredDeviceCount, int actualSuccessCount, String grantedAtUtc) {

    public static final String READY_FOR_RELEASE = "READY_FOR_RELEASE";
}
