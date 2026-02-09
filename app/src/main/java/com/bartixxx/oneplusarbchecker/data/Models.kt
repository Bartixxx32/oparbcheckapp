package com.bartixxx.oneplusarbchecker.data

import com.google.gson.annotations.SerializedName

data class DeviceData(
    @SerializedName("device_name") val deviceName: String,
    @SerializedName("versions") val versions: Map<String, VersionData>
)

data class VersionData(
    @SerializedName("arb") val arb: Int,
    @SerializedName("regions") val regions: List<String>,
    @SerializedName("status") val status: String,
    @SerializedName("md5") val md5: String?
)
