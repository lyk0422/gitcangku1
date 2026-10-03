package com.example.starter.service;

/**
 * 业务异常：携带 HTTP 状态码、稳定错误码以及拒绝日志所需的业务上下文。
 * 409 表示状态/幂等冲突，422 表示时间倒退或区间交叉，404 表示资源不存在。
 */
public class BusinessException extends RuntimeException {

    private final int status;
    private final String code;
    private final String operation;
    private final String tagCode;
    private final String animalCode;
    private final String stationCode;

    private BusinessException(Builder builder) {
        super(builder.message);
        this.status = builder.status;
        this.code = builder.code;
        this.operation = builder.operation;
        this.tagCode = builder.tagCode;
        this.animalCode = builder.animalCode;
        this.stationCode = builder.stationCode;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String operation() {
        return operation;
    }

    public String tagCode() {
        return tagCode;
    }

    public String animalCode() {
        return animalCode;
    }

    public String stationCode() {
        return stationCode;
    }

    /**
     * 创建 409 冲突异常构建器。
     */
    public static Builder conflict(String code, String message) {
        return new Builder(409, code, message);
    }

    /**
     * 创建 422 时间语义异常构建器。
     */
    public static Builder unprocessable(String code, String message) {
        return new Builder(422, code, message);
    }

    /**
     * 创建 404 不存在异常构建器。
     */
    public static Builder notFound(String code, String message) {
        return new Builder(404, code, message);
    }

    /**
     * 业务异常构建器。
     */
    public static final class Builder {
        private final int status;
        private final String code;
        private final String message;
        private String operation;
        private String tagCode;
        private String animalCode;
        private String stationCode;

        private Builder(int status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }

        public Builder operation(String operation) {
            this.operation = operation;
            return this;
        }

        public Builder tagCode(String tagCode) {
            this.tagCode = tagCode;
            return this;
        }

        public Builder animalCode(String animalCode) {
            this.animalCode = animalCode;
            return this;
        }

        public Builder stationCode(String stationCode) {
            this.stationCode = stationCode;
            return this;
        }

        public BusinessException build() {
            return new BusinessException(this);
        }
    }
}