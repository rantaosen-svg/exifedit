package com.photoedit.app.domain
import java.time.LocalDateTime

data class GpsCoordinates(val latitude: Double, val longitude: Double, val altitudeMeters: Double? = null)

data class PhotoMetadata(
    val takenAt: LocalDateTime? = null,
    val gps: GpsCoordinates? = null,
    val placeName: String? = null,
    val make: String? = null,
    val model: String? = null,
    val fNumber: Double? = null,
    val shutterSeconds: Double? = null,
    val iso: Int? = null,
    val focalLengthMm: Double? = null,
    val orientation: Int = 1,
)

enum class MetadataField { TAKEN_AT, GPS, MODEL, F_NUMBER, SHUTTER, ISO, FOCAL }

fun changedFields(old: PhotoMetadata, new: PhotoMetadata): Set<MetadataField> = buildSet {
    if (old.takenAt != new.takenAt) add(MetadataField.TAKEN_AT)
    if (old.gps != new.gps) add(MetadataField.GPS)
    if (old.model != new.model) add(MetadataField.MODEL)
    if (old.fNumber != new.fNumber) add(MetadataField.F_NUMBER)
    if (old.shutterSeconds != new.shutterSeconds) add(MetadataField.SHUTTER)
    if (old.iso != new.iso) add(MetadataField.ISO)
    if (old.focalLengthMm != new.focalLengthMm) add(MetadataField.FOCAL)
}
