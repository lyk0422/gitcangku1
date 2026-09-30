package com.example.starter.archival.web.dto;

/**
 * 页面归还（调拨出库）结果。归还后该页在本批次不再占用，可入组其他进行中批次。
 *
 * @param batchNo     批次号
 * @param accessionNo 馆藏号
 */
public record ReleaseResult(String batchNo, String accessionNo) {
}
