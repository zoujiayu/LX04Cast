package com.plx.lx04cast.dlna

/**
 * Which app is casting over DLNA, from the User-Agent or the media host.
 * Copied from CastBay (GPL-3.0) and stripped of Android string resources.
 */
object DlnaSender {
    const val IQIYI = "iqiyi"
    const val BILIBILI = "bilibili"
    const val NETEASE_MUSIC = "netease_music"
    const val QQ_MUSIC = "qq_music"
    const val TENCENT_VIDEO = "tencent_video"
    const val YOUKU = "youku"
    const val MANGO_TV = "mango_tv"
    const val KUGOU = "kugou"
    const val KUWO = "kuwo"

    fun displayName(id: String): String = when (id) {
        IQIYI -> "爱奇艺"
        BILIBILI -> "哔哩哔哩"
        NETEASE_MUSIC -> "网易云音乐"
        QQ_MUSIC -> "QQ音乐"
        TENCENT_VIDEO -> "腾讯视频"
        YOUKU -> "优酷"
        MANGO_TV -> "芒果TV"
        KUGOU -> "酷狗音乐"
        KUWO -> "酷我音乐"
        else -> ""
    }

    private val PATTERNS = listOf(
        "iqiyi" to IQIYI,
        "bili" to BILIBILI,
        "cloudmusic" to NETEASE_MUSIC,
        "netease" to NETEASE_MUSIC,
        "qqmusic" to QQ_MUSIC,
        "qqlive" to TENCENT_VIDEO,
        "tencentvideo" to TENCENT_VIDEO,
        "youku" to YOUKU,
        "mgtv" to MANGO_TV,
        "imgotv" to MANGO_TV,
        "kugou" to KUGOU,
        "kuwo" to KUWO,
    )

    private val HOSTS = listOf(
        "126.net" to NETEASE_MUSIC,
        "qqmusic" to QQ_MUSIC,
        "iqiyi.com" to IQIYI,
        "bilivideo" to BILIBILI,
        "kugou" to KUGOU,
        "kuwo" to KUWO,
        "youku" to YOUKU,
        "mgtv" to MANGO_TV,
    )

    fun fromUrl(url: String?): String {
        val host = url?.substringAfter("://")?.substringBefore('/')?.substringBefore(':')?.lowercase() ?: return ""
        return HOSTS.firstOrNull { (fragment, _) -> fragment in host }?.second.orEmpty()
    }

    fun fromUserAgent(userAgent: String?): String {
        val agent = userAgent?.lowercase() ?: return ""
        return PATTERNS.firstOrNull { (fragment, _) -> fragment in agent }?.second.orEmpty()
    }
}
