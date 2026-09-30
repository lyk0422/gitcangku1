package com.example.starter.archival.web.dto;

import java.util.List;

/**
 * 档案页处理历史查询结果：馆藏号 + 全部处理轮次（含批次状态、pH 前后值、药剂来源）。
 *
 * @param accessionNo 馆藏号
 * @param rounds      处理轮次列表，按时间升序
 */
public record PageHistoryResponse(String accessionNo, List<RoundView> rounds) {
}
