package com.example.starter.error;

import java.util.List;

/** 整批拒绝（422）：缺测、终测过高、归属冲突或数量超限，携带涉及的电芯编号。 */
public class RejectionException extends ApiException {

    private final List<String> cellCodes;

    public RejectionException(String message, List<String> cellCodes) {
        super(422, message);
        this.cellCodes = List.copyOf(cellCodes);
    }

    public List<String> cellCodes() {
        return cellCodes;
    }
}