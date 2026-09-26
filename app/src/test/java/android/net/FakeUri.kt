package android.net

import android.os.Parcel

/**
 * JVM 单测用最小 Uri 实例（避免 Robolectric）：android.net.Uri 是无参构造为
 * 包私有抽象类的 stub，测试侧只能在同包下子类化才能调用其构造器。
 * ViewModel 只把 Uri 当不透明身份（存储/相等比较/透传给 fake），故各成员给固定值即可。
 */
internal class FakeUri(val name: String) : Uri() {
    override fun toString(): String = name
    override fun equals(other: Any?): Boolean = other is FakeUri && other.name == name
    override fun hashCode(): Int = name.hashCode()
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    override fun buildUpon(): Uri.Builder = throw UnsupportedOperationException()
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getEncodedPath(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getEncodedSchemeSpecificPart(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getFragment(): String? = null
    override fun getHost(): String? = null
    override fun getLastPathSegment(): String? = null
    override fun getPath(): String? = null
    override fun getPathSegments(): MutableList<String> = mutableListOf()
    override fun getPort(): Int = -1
    override fun getQuery(): String? = null
    override fun getScheme(): String? = "content"
    override fun getSchemeSpecificPart(): String = name
    override fun getUserInfo(): String? = null
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
}
