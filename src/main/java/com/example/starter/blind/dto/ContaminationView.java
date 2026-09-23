package com.example.starter.blind.dto;

import java.util.List;

/**
 * 污染闭包视图：某个参与者当前版本下被污染的操作者集合，不含处理代码。
 *
 * @param experimentId  实验编号
 * @param participantId 参与者编号
 * @param version       闭包版本号，从 1 开始，新增披露产生新版本
 * @param status        版本状态：OPEN=可继续追加披露；CLOSED=已被隔离单冻结
 * @param actors        污染闭包内的操作者编号（去重排序）
 * @param computedAt    版本生成时间，Unix 毫秒，UTC
 */
public record ContaminationView(
        String experimentId,
        String participantId,
        int version,
        String status,
        List<String> actors,
        long computedAt
) {
}
