package com.example.starter.plan.model;

import java.util.List;

/**
 * 站台能力的一个不可变历史版本快照。历史发布记录通过 capabilityVersion 指向当时版本。
 *
 * @param id              主键
 * @param platformId      站台业务键
 * @param version         能力版本号
 * @param maxLengthMeters 该版本长度上限，单位米
 * @param powerSystems    该版本兼容供电制式集合（归一化去重字典序）
 * @param accessible      该版本是否具备无障碍服务能力
 * @param createdAt       该版本产生时刻，UTC 毫秒
 */
public record PlatformCapabilityHistory(long id, String platformId, int version,
                                        int maxLengthMeters, List<String> powerSystems,
                                        boolean accessible, long createdAt) {
}
