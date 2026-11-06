package com.musicglass.desktop

/** Strict v1 version syntax; deployment/version eligibility belongs to policy. */
internal class UpdateVersion private constructor(val major: Int, val minor: Int, val patch: Int) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun equals(other: Any?) = other is UpdateVersion && compareTo(other) == 0
    override fun hashCode() = 31 * (31 * major + minor) + patch
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): UpdateVersion? {
            if (value.length !in 5..32) return null
            val parts = value.split('.')
            if (parts.size != 3) return null
            val numbers = parts.map {
                if (it.isEmpty() || it.any { c -> c !in '0'..'9' } || (it.length > 1 && it[0] == '0')) return null
                it.toIntOrNull() ?: return null
            }
            return UpdateVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}
