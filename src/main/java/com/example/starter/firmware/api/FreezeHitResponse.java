package com.example.starter.firmware.api;

import java.util.List;

/**
 * 范围命中查询结果：matches 为范围命中的 ACTIVE 冻结令（含各自窗口是否生效）；
 * hit 为真表示当前时刻存在窗口生效中的命中冻结令（即此刻会被冻结）。
 */
public record FreezeHitResponse(boolean hit, List<FreezeOrderView> matches) {
}
