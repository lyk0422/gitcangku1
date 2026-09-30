package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementRevision;
import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierReview;

/**
 * 不可变事件载荷构造与 JSON 序列化。为保证“字段、排序、null 含义稳定”，
 * 不依赖对象映射器的反射顺序：统一用 {@link LinkedHashMap} 按固定键序写入，
 * BigDecimal 以 toPlainString 作为 JSON 数字保留精度，null 显式输出。
 */
final class EventPayloads {

    private EventPayloads() {
    }

    static Map<String, Object> revisionCreated(MeasurementRevision revision) {
        Map<String, Object> map = ordered();
        map.put("revisionNo", revision.revisionNo());
        map.put("targetValue", revision.targetValue());
        map.put("tolerance", revision.tolerance());
        map.put("lowerLimit", revision.lowerLimit());
        map.put("upperLimit", revision.upperLimit());
        map.put("reason", revision.reason());
        map.put("createdBy", revision.createdBy());
        map.put("createdAt", revision.createdAt());
        return map;
    }

    static Map<String, Object> outlierDetected(OutlierRecord outlier, String measurementKey) {
        Map<String, Object> map = ordered();
        map.put("outlierKey", outlier.outlierKey());
        map.put("revisionNo", outlier.revisionNo());
        map.put("measurementKey", measurementKey);
        map.put("measurementId", outlier.measurementId());
        map.put("targetValue", outlier.targetValue());
        map.put("tolerance", outlier.tolerance());
        map.put("lowerLimit", outlier.lowerLimit());
        map.put("upperLimit", outlier.upperLimit());
        map.put("rawReading", outlier.rawReading());
        map.put("computedValue", outlier.computedValue());
        map.put("deviation", outlier.deviation());
        map.put("excess", outlier.excess());
        map.put("submittedBy", outlier.submittedBy());
        map.put("detectedAt", outlier.detectedAt());
        return map;
    }

    static Map<String, Object> review(OutlierRecord outlier, OutlierReview review) {
        Map<String, Object> map = ordered();
        map.put("outlierKey", outlier.outlierKey());
        map.put("reviewer", review.reviewer());
        map.put("decision", review.decision().name());
        map.put("reason", review.reason());
        map.put("measurementRevision", review.measurementRevision());
        map.put("certificateId", review.certificateId());
        map.put("stale", review.stale());
        map.put("createdAt", review.createdAt());
        return map;
    }

    static Map<String, Object> verdict(OutlierRecord outlier, List<OutlierReview> activeReviews) {
        Map<String, Object> map = ordered();
        map.put("outlierKey", outlier.outlierKey());
        map.put("verdict", outlier.status().name());
        List<Object> reviewers = new ArrayList<>();
        for (OutlierReview r : activeReviews) {
            Map<String, Object> item = ordered();
            item.put("reviewer", r.reviewer());
            item.put("decision", r.decision().name());
            item.put("stale", r.stale());
            reviewers.add(item);
        }
        map.put("reviewers", reviewers);
        map.put("decidedAt", outlier.decidedAt());
        return map;
    }

    static Map<String, Object> recompute(int revisionNo, BatchStatistics stats, List<String> excludedKeys,
                                         java.time.Instant recomputedAt) {
        Map<String, Object> map = ordered();
        map.put("revisionNo", revisionNo);
        map.put("statistics", statistics(stats));
        map.put("excludedOutlierKeys", List.copyOf(excludedKeys));
        map.put("recomputedAt", recomputedAt);
        return map;
    }

    static Map<String, Object> statistics(BatchStatistics stats) {
        Map<String, Object> map = ordered();
        map.put("submittedTotal", stats.submittedTotal());
        map.put("excludedCount", stats.excludedCount());
        map.put("activeCount", stats.activeCount());
        map.put("outlierTotal", stats.outlierTotal());
        map.put("pendingOutlierCount", stats.pendingOutlierCount());
        map.put("acceptedOutlierCount", stats.acceptedOutlierCount());
        map.put("rejectedOutlierCount", stats.rejectedOutlierCount());
        map.put("requiredPendingOutliers", BatchStatistics.REQUIRED_PENDING_OUTLIERS);
        map.put("pendingOutlierGap", stats.pendingOutlierGap());
        map.put("activeMean", stats.activeMean());
        map.put("activeMin", stats.activeMin());
        map.put("activeMax", stats.activeMax());
        return map;
    }

    static Map<String, Object> release(int revisionNo, String releaseBatchId, String releasedBy,
                                       java.time.Instant releasedAt, List<Measurement> released,
                                       BatchStatistics stats) {
        Map<String, Object> map = ordered();
        map.put("revisionNo", revisionNo);
        map.put("releaseBatchId", releaseBatchId);
        map.put("releasedBy", releasedBy);
        map.put("releasedAt", releasedAt);
        List<Object> keys = new ArrayList<>();
        for (Measurement m : released) {
            keys.add(m.measurementKey());
        }
        map.put("released", keys);
        map.put("statistics", statistics(stats));
        return map;
    }

    static String toJson(Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder();
        appendValue(sb, payload);
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                appendValue(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendValue(sb, item);
            }
            sb.append(']');
        } else if (value instanceof BigDecimal decimal) {
            sb.append(decimal.toPlainString());
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof java.time.Instant instant) {
            appendString(sb, instant.toString());
        } else {
            appendString(sb, String.valueOf(value));
        }
    }

    private static void appendString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static Map<String, Object> ordered() {
        return new LinkedHashMap<>();
    }
}
