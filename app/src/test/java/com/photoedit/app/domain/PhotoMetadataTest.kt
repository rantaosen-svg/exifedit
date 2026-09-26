package com.photoedit.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class PhotoMetadataTest {
    @Test fun unchangedYieldsEmptySet() {
        val a = PhotoMetadata(takenAt = LocalDateTime.now(), gps = GpsCoordinates(1.0, 2.0))
        assertEquals(emptySet<MetadataField>(), changedFields(a, a.copy()))
    }
    @Test fun detectsTimeAndGpsChange() {
        val a = PhotoMetadata(takenAt = LocalDateTime.of(2020, 1, 1, 0, 0), gps = null)
        val b = a.copy(takenAt = LocalDateTime.of(2021, 1, 1, 0, 0), gps = GpsCoordinates(31.0, 121.0))
        assertEquals(setOf(MetadataField.TAKEN_AT, MetadataField.GPS), changedFields(a, b))
    }
    @Test fun gpsClearedIsAChange() {
        val a = PhotoMetadata(gps = GpsCoordinates(1.0, 2.0)); val b = a.copy(gps = null)
        assertEquals(setOf(MetadataField.GPS), changedFields(a, b))
    }
    @Test fun placeNameIsNotPersistedField() {
        val a = PhotoMetadata(placeName = "外滩"); assertEquals(emptySet<MetadataField>(), changedFields(a, a.copy(placeName = "陆家嘴")))
    }
}
