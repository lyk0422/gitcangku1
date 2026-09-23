package com.example.starter.blind.dto;

import java.util.List;

/**
 * 污染闭包查询视图：只暴露操作者闭包与版本信息，绝不返回处理代码。
 *
 * @param experimentId  实验编号
 * @param participantId 参与者编号
 * @param version       当前开放版本号
 * @param versionStatus 当前版本状态 OPEN / CLOSED
 * @param edgeCount     当前版本去重有向边数量
 * @param closure       当前开放版本的完整污染闭包（持密操作者编号升序），不含处理代码
 */
public record ContaminationView(
        String experimentId,
        String participantId,
        int version,
        String versionStatus,
        int edgeCount,
        List<String> closure
) {
}
