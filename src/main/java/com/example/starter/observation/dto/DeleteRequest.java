package com.example.starter.observation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 删除请求：仅当 expectedVersion 匹配当前版本时生成墓碑新版本。
 */
public class DeleteRequest {

    @NotNull
    @Positive
    private Integer expectedVersion;

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }
}
