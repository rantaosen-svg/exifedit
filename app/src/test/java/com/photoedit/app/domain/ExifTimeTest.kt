package com.photoedit.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ExifTimeTest {
    @Test fun formatsWithExifColonYear() {
        assertEquals("2026:09:26 14:03:05",
            ExifTime.format(LocalDateTime.of(2026, 9, 26, 14, 3, 5)))
    }
    @Test fun parsesValidAndRejectsGarbage() {
        assertEquals(LocalDateTime.of(2026, 9, 26, 14, 3, 5),
            ExifTime.parse("2026:09:26 14:03:05"))
        assertNull(ExifTime.parse("2026-09-26T14:03:05")); assertNull(ExifTime.parse(null))
    }
    @Test fun eastOffset() {
        assertEquals("+08:00", ExifTime.offsetOf(ZoneId.of("Asia/Shanghai")))
    }
    @Test fun westOffset() {
        // Etc/GMT+5 = 固定 UTC-5，无夏令时（brief 原文用 America/New_York，9 月为 -04:00 会随日期失败）
        assertEquals("-05:00", ExifTime.offsetOf(ZoneId.of("Etc/GMT+5")))
    }
}
