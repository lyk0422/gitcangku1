package com.example.starter.observation.exception;

/**
 * 记录已是墓碑（HTTP 410），拒绝新的修改和删除。
 */
public class ObservationGoneException extends RuntimeException {
    public ObservationGoneException(String message) {
        super(message);
    }
}
