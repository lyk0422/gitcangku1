package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 位置迁移前置条件失败（HTTP 422）：携带逐件证物的错误说明。
 * 申请或确认时任一证物不在库、封条异常、库位不等于源库位或版本不符即抛出，整单不生效。
 */
public class MovePreconditionException extends ApiException {

    private final List<ItemError> itemErrors;

    public MovePreconditionException(List<ItemError> itemErrors) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, buildMessage(itemErrors));
        this.itemErrors = List.copyOf(itemErrors);
    }

    /**
     * 逐项错误明细。
     */
    public List<ItemError> itemErrors() {
        return itemErrors;
    }

    private static String buildMessage(List<ItemError> itemErrors) {
        return "位置迁移前置条件不满足，共 " + itemErrors.size() + " 件证物异常";
    }

    /**
     * 单件证物错误项。
     *
     * @param evidenceKey 证物业务键
     * @param reason      逐项错误原因
     */
    public record ItemError(String evidenceKey, String reason) {
    }
}
