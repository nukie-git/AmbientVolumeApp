package com.nukie.ambientvolume.service

import java.util.ArrayDeque

/**
 * Helper class to calculate a moving average over a sliding window.
 * Useful for smoothing noisy sensor data like decibel levels.
 */
class MovingAverage(initialWindowSize: Int) {
    private var currentWindowSize = initialWindowSize
    private val window = ArrayDeque<Double>(currentWindowSize)
    private var sum = 0.0

    /**
     * Updates the window size dynamically. Trims excess samples from the front
     * instead of clearing, so transitions between intervals are smooth.
     */
    fun updateWindowSize(newSize: Int) {
        if (newSize == currentWindowSize) return
        currentWindowSize = newSize
        // Trim excess samples from the front (oldest data) rather than clearing
        while (window.size > currentWindowSize) {
            sum -= window.removeFirst()
        }
    }

    /**
     * Adds a new sample to the window and returns the current average.
     */
    fun add(sample: Double): Double {
        if (window.size >= currentWindowSize) {
            sum -= window.removeFirst()
        }
        window.addLast(sample)
        sum += sample
        return if (window.isEmpty()) 0.0 else sum / window.size
    }

    /**
     * Clears the current buffer.
     */
    fun clear() {
        window.clear()
        sum = 0.0
    }

    /**
     * Returns the current size of the buffer.
     */
    fun size(): Int = window.size

    /**
     * Returns the current average without adding a new sample.
     */
    fun getAverage(): Double {
        return if (window.isEmpty()) 0.0 else sum / window.size
    }
}
