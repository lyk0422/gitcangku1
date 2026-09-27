package com.example.starter.batch;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 效价核验未通过异常（HTTP 422）：放行或合批时当前效价低于最低放行要求。
 *
 * <p>携带当时计算产生的 FAIL 证据行；业务事务回滚后由服务层在独立事务中固化证据，
 * 失败命令不写入 command_log（失败不占键）。
 */
public class PotencyRejectedException extends ApiException {

    private final transient List<BatchRepository.EvidenceRow> evidenceRows;

    public PotencyRejectedException(String message, List<BatchRepository.EvidenceRow> evidenceRows) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "POTENCY_BELOW_THRESHOLD", message);
        this.evidenceRows = List.copyOf(evidenceRows);
    }

    public List<BatchRepository.EvidenceRow> evidenceRows() {
        return evidenceRows;
    }
}
