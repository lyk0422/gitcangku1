package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 拉取兼容判定证据，每次下发、阻断或豁免放行各固化一条，只增不改。
 *
 * @param id               判定记录ID
 * @param releaseId        判定所属发布单ID
 * @param deviceId         判定设备ID
 * @param outcome          GRANTED 直接满足；EXEMPTION 豁免放行；BLOCKED 阻断
 * @param matrixVersion    判定时兼容矩阵版本，无登记为0
 * @param exemptionVersion 判定时发布单豁免版本，无豁免为0
 * @param deviceModules    判定时设备模块清单快照，按模块名排序；后续上报与矩阵修订不改写
 * @param blockedModules   阻断明细，按模块名排序；非阻断为空列表
 * @param exemptedModules  本次命中的有效豁免模块名，按模块名排序；无命中为空列表
 * @param requestId        触发判定的拉取请求ID
 * @param checkedAtUtc     判定时刻，UTC，ISO-8601
 */
public record CompatibilityCheck(long id, long releaseId, String deviceId, CompatibilityOutcome outcome,
                                 int matrixVersion, int exemptionVersion, List<ModuleSnapshot> deviceModules,
                                 List<BlockedModule> blockedModules, List<String> exemptedModules,
                                 String requestId, String checkedAtUtc) {
}
