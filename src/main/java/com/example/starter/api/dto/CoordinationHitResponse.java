package com.example.starter.api.dto;

import java.util.List;
import java.util.Map;

/**
 * 锁文件命中的协调组依据：锁定时的组修订、命中元组与允许元组全集快照。
 * 历史锁的命中依据不随后续组修订或元组删改而改变。
 *
 * @param groupName     命中的协调组名称
 * @param groupRevision 锁定时的组修订号
 * @param matchedTuple  命中的允许元组（成员名 -> 版本，覆盖全部成员）
 * @param allowedTuples 锁定时的允许元组全集快照
 */
public record CoordinationHitResponse(
        String groupName,
        int groupRevision,
        Map<String, Integer> matchedTuple,
        List<Map<String, Integer>> allowedTuples) {
}
