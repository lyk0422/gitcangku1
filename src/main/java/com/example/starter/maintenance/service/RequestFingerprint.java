package com.example.starter.maintenance.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 幂等请求指纹：以结构化 JSON（固定键序、字符串逐字符转义）描述写操作身份，
 * 包含操作、资源及全部业务参数和期望版本；避免分隔符拼接在参数含特殊字符时碰撞。
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static Builder of(String operation) {
        return new Builder(operation);
    }

    /** 指纹构建器：按字段加入顺序序列化，同一请求恒产生同一串。 */
    public static final class Builder {

        private final Map<String, Object> fields = new LinkedHashMap<>();

        private Builder(String operation) {
            fields.put("operation", operation);
        }

        public Builder field(String key, Object value) {
            fields.put(key, value);
            return this;
        }

        public String build() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : fields.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                appendString(sb, entry.getKey());
                sb.append(':');
                appendValue(sb, entry.getValue());
            }
            return sb.append('}').toString();
        }

        private static void appendValue(StringBuilder sb, Object value) {
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                appendString(sb, String.valueOf(value));
            }
        }

        private static void appendString(StringBuilder sb, String text) {
            sb.append('"');
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
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
    }
}
