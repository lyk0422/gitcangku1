package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 跨对象查询响应：成功响应即合规快照，固化每个对象在查询时刻的 epoch、驻留区域与授权版本。
 *
 * @param snapshotId  快照标识（与 requestId 相同）
 * @param subjectKey  主体标识
 * @param purpose     用途
 * @param regions     本次允许的驻留区域集合（按字典序排序去重）
 * @param objects     对象快照列表（按 record_key 字典序排序，稳定排序与 null 语义）
 * @param objectCount 实际返回对象数量（实际值）
 * @param queriedAt   查询/快照生成时间（UTC）
 */
public record QueryResponse(String snapshotId, String subjectKey, Purpose purpose,
                            List<String> regions, List<SnapshotObject> objects,
                            int objectCount, Instant queriedAt) {

    /**
     * 单个对象的固化视图；后续迁移、撤回、清除均不改写已生成快照中的该对象。
     *
     * @param recordKey      记录键
     * @param epoch          查询时刻对象所属授权代次
     * @param region         查询时刻对象驻留区域代码
     * @param consentVersion 查询时刻对象授权版本
     * @param payload        查询时刻对象内容（合成字符串）
     */
    public record SnapshotObject(String recordKey, int epoch, String region,
                                 int consentVersion, String payload) {
    }
}
