package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 案件保留冻结总览：案件级冻结与该案件全部证物级冻结（含已解除、已到期），按发生顺序排列，
 * 另附案件下全部销毁申请历史。effective 为查询时刻按左闭右开计算的有效标识。
 *
 * @param caseKey      案件键
 * @param holds        案件级与证物级冻结记录（含解除链），按 id 升序
 * @param destructions 案件下全部证物的销毁申请历史，按 id 升序
 */
public record HoldChainView(
        String caseKey,
        List<HoldView> holds,
        List<DestructionView> destructions) {
}
