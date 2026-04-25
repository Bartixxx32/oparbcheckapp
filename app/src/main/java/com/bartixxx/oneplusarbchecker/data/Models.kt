package com.bartixxx.oneplusarbchecker.data

import com.google.gson.annotations.SerializedName

data class DeviceData(
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("expect_esim") val expectEsim: Boolean = false,
    @SerializedName("expect_barometer") val expectBarometer: Boolean = false,
    @SerializedName("versions") val versions: Map<String, VersionData>
)

data class VersionData(
    @SerializedName("arb") val arb: Int,
    @SerializedName("regions") val regions: List<String>,
    @SerializedName("status") val status: String,
    @SerializedName("md5") val md5: String?,
    @SerializedName("first_seen") val firstSeen: String? = null,
    @SerializedName("major") val major: Int? = null,
    @SerializedName("minor") val minor: Int? = null,
    @SerializedName("is_hardcoded") val isHardcoded: Boolean = false
)

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("body") val body: String? = null
)
