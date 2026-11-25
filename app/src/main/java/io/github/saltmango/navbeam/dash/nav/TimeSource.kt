package io.github.saltmango.navbeam.dash.nav

interface TimeSource {
    fun wallTimeMs(): Long
    fun monotonicNanos(): Long
}
