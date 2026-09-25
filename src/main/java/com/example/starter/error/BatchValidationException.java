package com.example.starter.error;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 批量入库清单数据格式非法（HTTP 422），携带逐项失败原因。
 * 抛出时整批不创建任何证物，且不占用幂等键。
 */
public class BatchValidationException extends RuntimeException {

    /**
     * 单项失败原因。
     *
     * @param index       清单下标（从 0 开始）
     * @param evidenceKey 证物业务键；缺失时为 NULL
     * @param reason      失败原因
     */
    public record ItemError(int index, String evidenceKey, String reason) {
    }

    private final transient List<ItemError> errors;

    public BatchValidationException(List<ItemError> errors) {
        super("清单数据格式非法，共 " + errors.size() + " 项");
        this.errors = List.copyOf(errors);
    }

    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    public List<ItemError> errors() {
        return errors;
    }
}
