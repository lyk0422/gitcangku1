package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id               任务ID
 * @param releaseId        所属发布单ID
 * @param deviceId         设备ID
 * @param status           任务状态
 * @param firstResult      首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param matrixVersion    下发时兼容矩阵版本，无登记为0；矩阵后续修订不改写
 * @param exemptionVersion 下发时发布单豁免版本，无豁免为0；豁免后续修订不改写
 * @param grantedVia       下发途径：DIRECT 全部模块直接满足；EXEMPTION 存在模块依靠有效豁免放行
 * @param deviceModulesJson 下发时设备模块清单快照JSON（模块名、版本、摘要，按模块名排序）；设备后续上报不改写
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, int matrixVersion, int exemptionVersion,
                          GrantedVia grantedVia, String deviceModulesJson) {
}
