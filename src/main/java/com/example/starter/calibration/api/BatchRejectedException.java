package com.example.starter.calibration.api;

import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;

/**
 * 批量放行被拒绝：任一项校验失败则整批拒绝，携带各项失败原因，不产生部分成功。
 * 失败原因全部为“请求无法被处理”类（预算未登记、阈值缺失、合成超阈值）时返回 422；
 * 其余为与当前状态冲突（待放行状态、合格性、证书撤销、同人等），返回 409。
 * 混合存在时按 422 优先，以便客户端区分“数据不齐”与“状态冲突”。
 */
public class BatchRejectedException extends ApiException {

    /** 422 类单项原因：请求语义无法处理（数据缺失或不满足新增的不确定度前置条件）。 */
    private static final Set<String> UNPROCESSABLE_REASONS = Set.of(
            "BUDGET_NOT_REGISTERED",
            "THRESHOLD_NOT_FOUND",
            "UNCERTAINTY_EXCEEDS_THRESHOLD");

    private final List<ItemFailure> failures;

    public BatchRejectedException(List<ItemFailure> failures) {
        super(resolveStatus(failures), resolveCode(failures), resolveMessage(failures));
        this.failures = List.copyOf(failures);
    }

    public List<ItemFailure> getFailures() {
        return failures;
    }

    private static boolean anyUnprocessable(List<ItemFailure> failures) {
        return failures.stream()
                .flatMap(f -> f.reasons().stream())
                .anyMatch(UNPROCESSABLE_REASONS::contains);
    }

    private static HttpStatus resolveStatus(List<ItemFailure> failures) {
        return anyUnprocessable(failures)
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.CONFLICT;
    }

    private static String resolveCode(List<ItemFailure> failures) {
        return anyUnprocessable(failures) ? "BATCH_UNPROCESSABLE" : "BATCH_REJECTED";
    }

    private static String resolveMessage(List<ItemFailure> failures) {
        return anyUnprocessable(failures)
                ? "批量放行无法处理：存在预算/阈值不满足条件的测量"
                : "批量放行被拒绝：存在不满足放行条件的测量";
    }
}
