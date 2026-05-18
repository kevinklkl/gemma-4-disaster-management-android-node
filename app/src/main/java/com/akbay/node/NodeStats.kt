package com.akbay.node

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object NodeStats {
    private val _processedRequests = MutableStateFlow(0)
    val processedRequests: StateFlow<Int> = _processedRequests

    private val _tokensProcessed = MutableStateFlow(0L)
    val tokensProcessed: StateFlow<Long> = _tokensProcessed

    private val _failedRequests = MutableStateFlow(0)
    val failedRequests: StateFlow<Int> = _failedRequests

    private val _deviceTemp = MutableStateFlow<Double?>(null)
    val deviceTemp: StateFlow<Double?> = _deviceTemp

    fun addRequest(success: Boolean) {
        if (success) {
            _processedRequests.value += 1
        } else {
            _failedRequests.value += 1
        }
    }

    fun addTokens(count: Int) {
        _tokensProcessed.value += count
    }

    fun setTemp(temp: Double) {
        _deviceTemp.value = temp
    }

    fun reset() {
        _processedRequests.value = 0
        _tokensProcessed.value = 0
        _failedRequests.value = 0
    }
}
