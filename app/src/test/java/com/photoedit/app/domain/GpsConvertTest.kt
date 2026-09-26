package com.photoedit.app.domain

import org.junit.Assert.assertEquals
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
}
