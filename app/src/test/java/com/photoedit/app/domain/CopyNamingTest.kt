package com.photoedit.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CopyNamingTest {
    @Test fun firstCopy() {
        assertEquals("IMG_1234_副本.jpg", CopyNaming.next("IMG_1234.jpg", emptySet()))
    }
    @Test fun incrementsOnConflict() {
        val taken = setOf("IMG_1234_副本.jpg", "IMG_1234_副本2.jpg")
        assertEquals("IMG_1234_副本3.jpg", CopyNaming.next("IMG_1234.jpg", taken))
    }
    @Test fun noExtensionAppendsJpg() {
        assertEquals("IMG_9_副本.jpg", CopyNaming.next("IMG_9", emptySet()))
    }
}
