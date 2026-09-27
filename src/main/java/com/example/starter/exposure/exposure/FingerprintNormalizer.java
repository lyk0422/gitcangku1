package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ReceiptBatchRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 幂等指纹规范化工具。指纹以 JSON 结构序列化（键值转义由 JSON 编码器完成，
 * 禁用未转义分隔符拼接），覆盖操作者、操作类型、规范化对象集合与全部结果相关字段。
 *
 * <p>批量回执的对象集合按预占编号升序规范化，因此集合顺序变化不影响指纹，
 * 而任一字段（归属、回执时刻）变化都会产生不同指纹。</p>
 */
final class FingerprintNormalizer {

    private FingerprintNormalizer() {
    }

    static String createCampaign(ObjectMapper mapper, String campaignId, int dailyTotalCap,
                                 int perVisitorDailyCap, int attributionWindowMinutes, String operatorId) {
        Map<String, Object> root = base("CREATE_CAMPAIGN", operatorId);
        root.put("campaignId", campaignId);
        root.put("dailyTotalCap", dailyTotalCap);
        root.put("perVisitorDailyCap", perVisitorDailyCap);
        root.put("attributionWindowMinutes", attributionWindowMinutes);
        return write(mapper, root);
    }

    static String updateWindow(ObjectMapper mapper, String campaignId, String operatorId,
                               int attributionWindowMinutes) {
        Map<String, Object> root = base("UPDATE_WINDOW", operatorId);
        root.put("campaignId", campaignId);
        root.put("attributionWindowMinutes", attributionWindowMinutes);
        return write(mapper, root);
    }

    static String apply(ObjectMapper mapper, String campaignId, String visitorId) {
        Map<String, Object> root = base("APPLY", null);
        root.put("campaignId", campaignId);
        root.put("visitorId", visitorId);
        return write(mapper, root);
    }

    static String reservationAction(ObjectMapper mapper, String operation, String reservationId) {
        Map<String, Object> root = base(operation, null);
        root.put("reservationId", reservationId);
        return write(mapper, root);
    }

    /**
     * 批量回执指纹：操作者、操作类型固定，对象集合按预占编号升序排列后逐项记录
     * 全部结果相关字段（预占、公告、访客、回执业务时刻）。
     */
    static String receiptBatch(ObjectMapper mapper, ReceiptBatchRequest request) {
        Map<String, Object> root = base("SUBMIT_RECEIPTS", request.operatorId());
        List<ReceiptBatchRequest.ReceiptItem> sorted = new ArrayList<>(request.receipts());
        sorted.sort((a, b) -> {
            int byReservation = a.reservationId().compareTo(b.reservationId());
            if (byReservation != 0) {
                return byReservation;
            }
            int byCampaign = a.campaignId().compareTo(b.campaignId());
            if (byCampaign != 0) {
                return byCampaign;
            }
            int byVisitor = a.visitorId().compareTo(b.visitorId());
            if (byVisitor != 0) {
                return byVisitor;
            }
            return Long.compare(a.receiptAtUtc(), b.receiptAtUtc());
        });
        List<Map<String, Object>> items = new ArrayList<>();
        for (ReceiptBatchRequest.ReceiptItem item : sorted) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("reservationId", item.reservationId());
            entry.put("campaignId", item.campaignId());
            entry.put("visitorId", item.visitorId());
            entry.put("receiptAtUtc", item.receiptAtUtc());
            items.add(entry);
        }
        root.put("receipts", items);
        return write(mapper, root);
    }

    private static Map<String, Object> base(String operation, String operatorId) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("operation", operation);
        root.put("operatorId", operatorId);
        return root;
    }

    private static String write(ObjectMapper mapper, Map<String, Object> root) {
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to normalize idempotency fingerprint", e);
        }
    }
}
