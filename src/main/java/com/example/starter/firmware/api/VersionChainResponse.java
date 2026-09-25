package com.example.starter.firmware.api;

import java.util.List;

/**
 * 版本链明细：从指定版本出发沿前置链到链起点的有序节点列表。
 */
public record VersionChainResponse(String version, List<VersionView> chain) {
}
