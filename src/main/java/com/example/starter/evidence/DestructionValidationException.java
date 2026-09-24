package com.example.starter.evidence;

import com.example.starter.error.ApiException;

import java.util.List;

/**
 * 销毁令入列校验异常：任一件证物不合格时整单 422，且逐件返回原因，不创建销毁令。
 */
public class DestructionValidationException extends ApiException {

    private final List<ItemReason> itemReasons;

    public DestructionValidationException(List<ItemReason> itemReasons) {
        super(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "销毁令入列证物校验失败");
        this.itemReasons = List.copyOf(itemReasons);
    }

    public List<ItemReason> itemReasons() {
        return itemReasons;
    }

    /**
     * 单件证物不合格原因。
     *
     * @param evidenceKey 证物业务键
     * @param reason      机器可读原因码
     * @param detail      人类可读说明
     */
    public record ItemReason(String evidenceKey, String reason, String detail) {
    }
}
