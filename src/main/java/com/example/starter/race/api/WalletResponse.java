package com.example.starter.race.api;

/**
 * 选手钱包响应。
 *
 * @param raceId  赛事ID
 * @param bib     参赛号
 * @param balance 当前可用余额（人民币元，非负整数，不含已冻结保证金）
 */
public record WalletResponse(String raceId, String bib, int balance) {
}
