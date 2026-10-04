package com.example.starter.plan.model;

/**
 * 列车牵引方式：ELECTRIC 电力（要求占用全程区段供电）；
 * DIESEL 柴油（要求占用全程区段允许柴油车通行）。
 */
public enum TractionMode {
    ELECTRIC,
    DIESEL
}