package com.example.starter.firmware.api;

import java.util.List;

/**
 * 版本链明细视图：chain 从该版本自身开始，沿前置链接回溯到链起点。
 */
public record VersionChainView(String version, String predecessorVersion, List<String> chain) {
}
