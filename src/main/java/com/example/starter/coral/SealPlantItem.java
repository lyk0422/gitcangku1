package com.example.starter.coral;

/**
 * 封存快照中的单株条目，固化封存时刻的植株编号与状态。
 */
public record SealPlantItem(String plantCode, TransplantStatus status) {
}