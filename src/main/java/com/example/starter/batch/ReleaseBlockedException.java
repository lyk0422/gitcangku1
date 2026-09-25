package com.example.starter.batch;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 放行持续门禁未通过异常（422）：携带当前阻断放行的偏差业务键清单。
 * MAJOR 未裁决偏差与未经质控确认的 MINOR 偏差分别列出。
 */
public class ReleaseBlockedException extends ApiException {

    private final List<String> blockingMajorKeys;
    private final List<String> unconfirmedMinorKeys;

    public ReleaseBlockedException(List<String> blockingMajorKeys, List<String> unconfirmedMinorKeys) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "RELEASE_BLOCKED", buildMessage(blockingMajorKeys, unconfirmedMinorKeys));
        this.blockingMajorKeys = List.copyOf(blockingMajorKeys);
        this.unconfirmedMinorKeys = List.copyOf(unconfirmedMinorKeys);
    }

    public List<String> blockingMajorKeys() {
        return blockingMajorKeys;
    }

    public List<String> unconfirmedMinorKeys() {
        return unconfirmedMinorKeys;
    }

    private static String buildMessage(List<String> majorKeys, List<String> minorKeys) {
        StringBuilder sb = new StringBuilder("存在未解除的储运偏差门禁");
        if (!majorKeys.isEmpty()) {
            sb.append("；未裁决 MAJOR 偏差: ").append(String.join(", ", majorKeys));
        }
        if (!minorKeys.isEmpty()) {
            sb.append("；未经质控确认的 MINOR 偏差: ").append(String.join(", ", minorKeys));
        }
        return sb.toString();
    }
}
