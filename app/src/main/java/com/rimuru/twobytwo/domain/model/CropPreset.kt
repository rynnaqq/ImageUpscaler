package com.rimuru.twobytwo.domain.model

enum class CropPreset(val ratio: Double) {
    SQUARE(1.0),
    PORTRAIT_9_16(9.0 / 16.0),
    PORTRAIT_4_5(4.0 / 5.0),
    PRINT_4_6(4.0 / 6.0),
    PRINT_8_10(8.0 / 10.0),
}
