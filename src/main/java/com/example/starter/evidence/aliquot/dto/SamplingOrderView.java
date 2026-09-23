package com.example.starter.evidence.aliquot.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 联合取样单聚合只读视图：申请单 + 全部母样明细 + 审核历史 + 成功后的不可变映射。
 *
 * @param requestId          联合取样申请业务键
 * @param aliquotKey         子样业务键
 * @param custodianId        申请时的母样保管人
 * @param status             申请单状态
 * @param version            申请单当前版本号
 * @param items              全部母样取用明细
 * @param reviews            全部审核历史，按发生顺序
 * @param mappings           二次确认成功后的母样-数量-子样映射；未完成前为空
 * @param createdAt          申请时间（Asia/Shanghai）
 * @param confirmedAt        二次确认完成时间；NULL 表示未完成
 * @param decidedAt          拒绝或取消时间；NULL 表示未终态
 */
public record SamplingOrderView(
        String requestId,
        String aliquotKey,
        String custodianId,
        String status,
        long version,
        List<SamplingItemView> items,
        List<ReviewView> reviews,
        List<AliquotMappingView> mappings,
        LocalDateTime createdAt,
        LocalDateTime confirmedAt,
        LocalDateTime decidedAt) {
}
