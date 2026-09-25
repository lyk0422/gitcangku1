package com.example.starter.error;

import java.util.List;

/**
 * 批量入库逐项数据格式非法（HTTP 422）。携带每项的下标、证物键与原因，
 * 整批不创建任何证物，且不占用幂等键。
 */
public class ItemValidationException extends RuntimeException {

    private final List<ItemError> errors;

    public ItemValidationException(List<ItemError> errors) {
        super("清单存在非法数据项: " + errors.size() + " 项");
        this.errors = List.copyOf(errors);
    }

    public List<ItemError> errors() {
        return errors;
    }

    /**
     * 单项非法原因。
     *
     * @param index       清单下标（从 0 开始）
     * @param evidenceKey 证物业务键；键本身非法时为 null
     * @param reason      非法原因描述
     */
    public record ItemError(int index, String evidenceKey, String reason) {
    }
}
