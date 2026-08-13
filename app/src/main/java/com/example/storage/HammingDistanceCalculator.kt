package com.example.storage

interface HammingDistanceCalculator {
    fun calculateHammingDistance(hash1: String, hash2: String): Int
}
