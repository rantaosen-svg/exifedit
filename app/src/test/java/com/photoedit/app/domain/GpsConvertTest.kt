package com.photoedit.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GpsConvertTest {
    @Test fun shanghaiLatitudeRoundTrip() {
        val dms = GpsConvert.fromDecimalLatitude(31.2304)!!
        assertEquals('N', dms.ref)
        assertEquals(31.2304, GpsConvert.toDecimal(dms.degrees, dms.minutes, dms.seconds, dms.ref)!!, 1e-5)
    }
    @Test fun southLatitudeIsNegative() {
        val dms = GpsConvert.fromDecimalLatitude(-33.8688)!!
        assertEquals('S', dms.ref)
        assertEquals(-33.8688, GpsConvert.toDecimal(dms.degrees, dms.minutes, dms.seconds, dms.ref)!!, 1e-5)
    }
    @Test fun invalidLatitudeRejected() { assertNull(GpsConvert.fromDecimalLatitude(91.0)) }
    @Test fun invalidLongitudeRejected() { assertNull(GpsConvert.fromDecimalLongitude(-200.0)) }
    @Test fun invalidRefReturnsNull() { assertNull(GpsConvert.toDecimal(1, 2, 3.0, 'X')) }

    // Minor①：NaN 与区间比较恒为 false（旧实现漏网），±Infinity 一并显式拒绝
    @Test fun nanLatitudeRejected() { assertNull(GpsConvert.fromDecimalLatitude(Double.NaN)) }
    @Test fun nanLongitudeRejected() { assertNull(GpsConvert.fromDecimalLongitude(Double.NaN)) }
    @Test fun infiniteLatitudeRejected() {
        assertNull(GpsConvert.fromDecimalLatitude(Double.POSITIVE_INFINITY))
        assertNull(GpsConvert.fromDecimalLatitude(Double.NEGATIVE_INFINITY))
    }
    @Test fun infiniteLongitudeRejected() {
        assertNull(GpsConvert.fromDecimalLongitude(Double.POSITIVE_INFINITY))
        assertNull(GpsConvert.fromDecimalLongitude(Double.NEGATIVE_INFINITY))
    }
    @Test fun boundaryValuesStillAccepted() {
        assertNotNull(GpsConvert.fromDecimalLatitude(90.0))
        assertNotNull(GpsConvert.fromDecimalLatitude(-90.0))
        assertNotNull(GpsConvert.fromDecimalLongitude(180.0))
        assertNotNull(GpsConvert.fromDecimalLongitude(-180.0))
    }
}
