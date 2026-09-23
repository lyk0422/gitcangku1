package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 当前有效委托链只读响应：主体到指定处理方的最短有效路径。
 *
 * @param subjectKey 授权主体标识
 * @param purpose    用途
 * @param epoch      授权代次
 * @param processorKey 查询的目标处理方；主体直查时与 subjectKey 相同
 * @param edges      有序有效边链；主体直查为空列表
 */
public record ChainResponse(String subjectKey, Purpose purpose, int epoch,
                            String processorKey, List<ChainEdgeResponse> edges) {
}
