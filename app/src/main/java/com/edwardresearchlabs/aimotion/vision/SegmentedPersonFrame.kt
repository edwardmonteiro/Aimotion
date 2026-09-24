package com.edwardresearchlabs.aimotion.vision

import android.graphics.Bitmap

data class SegmentedPersonFrame(
    val bitmap: Bitmap,
    val timestampMs: Long
)
