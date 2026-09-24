package com.example.starter.evidence.destruction;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.destruction.dto.DestructionItemError;

import java.util.List;

/**
 * 创建销毁令整单不可处理异常（HTTP 422）：任一件入列证物不合格时抛出，
 * 携带按提交原序排列的逐件原因；此时不创建销毁令、不占用 commandKey。
 */
public class DestructionValidationException extends ApiException {

    private final transient List<DestructionItemError> itemErrors;

    public DestructionValidationException(String message, List<DestructionItemError> itemErrors) {
        super(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, message);
        this.itemErrors = List.copyOf(itemErrors);
    }

    public List<DestructionItemError> itemErrors() {
        return itemErrors;
    }
}
