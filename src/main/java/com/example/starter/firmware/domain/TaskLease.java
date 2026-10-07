package com.example.starter.firmware.domain;

/**
 * 任务执行租约：同一设备可能更换执行代理，旧代理不能在新代理接管后写回安装结果。
 *
 * @param id              任务ID
 * @param currentToken    当前租约令牌序号，从1开始，每次到期被接管时加一
 * @param currentAgentId  当前持有代理ID（非空）
 * @param expiresAtUtc    当前租约到期时刻，UTC，ISO-8601；有效期左闭右开
 * @param terminal        任务是否已一次终结（SUCCESS/FAILED）
 * @param terminalAgentId 首次终结回执的实际代理ID，未终结为 null
 * @param terminalToken   首次终结回执实际使用的令牌，未终结为 null
 */
public record TaskLease(long id, int currentToken, String currentAgentId, String expiresAtUtc,
                        boolean terminal, String terminalAgentId, Integer terminalToken) {

    /**
     * 当前时刻是否在租约有效期内：左闭右开，即 now &lt; 到期时刻。
     */
    public boolean isValidAt(java.time.Instant now) {
        return !terminal && now.isBefore(java.time.Instant.parse(expiresAtUtc));
    }
}
