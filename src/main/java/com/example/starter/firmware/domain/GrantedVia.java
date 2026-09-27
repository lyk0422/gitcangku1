package com.example.starter.firmware.domain;

/**
 * 任务下发途径：DIRECT 全部所需模块由设备直接满足；EXEMPTION 至少一个模块依靠发布单有效豁免放行。
 */
public enum GrantedVia {
    DIRECT,
    EXEMPTION
}
