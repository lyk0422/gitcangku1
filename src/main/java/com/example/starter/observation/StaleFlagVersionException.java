package com.example.starter.observation;

/**
 * 复核事务内部信号：检测到标记绑定版本与观测当前版本不一致（观测已产生新版本或已删除）。
 *
 * <p>该异常在复核事务内抛出以触发整体回滚（幂等占位不占键），
 * 由外层捕获后在独立事务中将标记条件化地转 STALE，再向客户端返回 410。
 */
class StaleFlagVersionException extends RuntimeException {

    private final String flagKey;
    private final int currentVersion;

    StaleFlagVersionException(String flagKey, int currentVersion) {
        super("flag version is no longer current: " + flagKey + ", currentVersion=" + currentVersion);
        this.flagKey = flagKey;
        this.currentVersion = currentVersion;
    }

    String flagKey() {
        return flagKey;
    }

    int currentVersion() {
        return currentVersion;
    }
}
