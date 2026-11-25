package io.github.saltmango.navbeam.dash.nav

data class CompiledRoute(
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val totalMeters: Double,
    val totalSeconds: Double,
    val cumulative: DoubleArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CompiledRoute

        if (geometry != other.geometry) return false
        if (maneuvers != other.maneuvers) return false
        if (totalMeters != other.totalMeters) return false
        if (totalSeconds != other.totalSeconds) return false
        if (!cumulative.contentEquals(other.cumulative)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = geometry.hashCode()
        result = 31 * result + maneuvers.hashCode()
        result = 31 * result + totalMeters.hashCode()
        result = 31 * result + totalSeconds.hashCode()
        result = 31 * result + cumulative.contentHashCode()
        return result
    }
}
