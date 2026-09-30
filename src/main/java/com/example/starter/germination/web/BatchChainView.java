package com.example.starter.germination.web;

import java.util.List;

/**
 * 按批次聚合的检测链、当前证书与放行状态视图。
 *
 * @param batchNo      批次编号
 * @param cropName     作物名称
 * @param tests        检测链（含已作废记录，按登记顺序）
 * @param certificate  当前证书；无证书为 null。证书内可见所依赖的两次检测编号
 * @param release      放行状态
 */
public record BatchChainView(String batchNo,
                             String cropName,
                             List<TestView> tests,
                             CertificateView certificate,
                             ReleaseView release) {
}
