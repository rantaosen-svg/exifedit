package com.photoedit.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Important#3（覆盖先截断风险）：overwrite 前置 OWNER_PACKAGE_NAME 判定的纯函数单测。
 * 判定语义：只有"已知且非本包"才允许在**打开输出流之前**直接 NeedsPermission；
 * 查询拿不到 owner（零权限下外部行不可见 / 列缺失 / 查询失败）必须维持直写尝试路径，
 * 不得因 Unknown 误伤本 app 自有文件的覆盖。
 */
class OwnerCheckTest {

    @Test fun `null owner 判 Unknown 维持现路径`() {
        assertEquals(OwnerCheck.Unknown, classifyOwnerCheck(null, "com.photoedit.app"))
    }

    @Test fun `本包 owner 判 Own`() {
        assertEquals(OwnerCheck.Own, classifyOwnerCheck("com.photoedit.app", "com.photoedit.app"))
    }

    @Test fun `外部包 owner 判 Foreign 可在开流前拦截`() {
        assertEquals(OwnerCheck.Foreign, classifyOwnerCheck("com.android.chrome", "com.photoedit.app"))
    }

    @Test fun `owner 判定只认全等 前缀相似不误判为本包`() {
        assertEquals(OwnerCheck.Foreign, classifyOwnerCheck("com.photoedit.app.extra", "com.photoedit.app"))
    }
}
