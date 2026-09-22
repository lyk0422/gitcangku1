package com.example.starter.blind;

/**
 * 路径标识与请求头的基础校验；避免超长或非法标识直接触发数据库错误。
 */
public final class RequestTokens {

    /** 各业务编号在数据库中的最大长度。 */
    public static final int MAX_ID_LENGTH = 64;

    private RequestTokens() {
    }

    /**
     * 校验非空且长度不超过上限的业务编号。
     */
    public static String requireId(String name, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(name + " 不能为空");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw ApiException.badRequest(name + " 长度不能超过 " + MAX_ID_LENGTH + " 个字符");
        }
        return value;
    }

    /**
     * 校验写操作的 X-Request-Id：非空且不超过数据库列长度。
     */
    public static String requireRequestId(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("写操作必须携带全局唯一 X-Request-Id 请求头");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw ApiException.badRequest("X-Request-Id 长度不能超过 " + MAX_ID_LENGTH + " 个字符");
        }
        return value;
    }
}
