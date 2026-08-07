package com.towerscope.ar.util

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sun / moon topocentric azimuth & elevation for heading calibration.
 * Azimuth is degrees clockwise from true north [0, 360).
 * Elevation is degrees above the horizon (negative = below).
 *
 * Algorithms: NOAA solar equations; Meeus low-precision lunar position.
 */
object CelestialBodies {

    enum class Body { SUN, MOON }

    data class Position(
        val body: Body,
        val azimuthDegrees: Double,
        val elevationDegrees: Double,
        /** 0–1; sun is always 1. */
        val illuminatedFraction: Double
    )

    /** Prefer the sun by day; fall back to a visible moon at night. */
    fun preferredCalibrationTarget(
        latitude: Double,
        longitude: Double,
        timeMillis: Long = System.currentTimeMillis()
    ): Position? {
        val sun = sunPosition(latitude, longitude, timeMillis)
        if (sun.elevationDegrees >= MIN_ELEVATION_DEGREES) return sun

        val moon = moonPosition(latitude, longitude, timeMillis)
        if (
            moon.elevationDegrees >= MIN_ELEVATION_DEGREES &&
            moon.illuminatedFraction >= MIN_MOON_ILLUMINATION
        ) {
            return moon
        }
        return null
    }

    fun sunPosition(
        latitude: Double,
        longitude: Double,
        timeMillis: Long = System.currentTimeMillis()
    ): Position {
        val jd = julianDate(timeMillis)
        val jc = (jd - 2451545.0) / 36525.0

        val geomMeanLong = (280.46646 + jc * (36000.76983 + jc * 0.0003032)) % 360.0
        val geomMeanAnom = 357.52911 + jc * (35999.05029 - 0.0001537 * jc)
        val eccent = 0.016708634 - jc * (0.000042037 + 0.0000001267 * jc)

        val sunEqCtr = sin(rad(geomMeanAnom)) * (1.914602 - jc * (0.004817 + 0.000014 * jc)) +
            sin(rad(2 * geomMeanAnom)) * (0.019993 - 0.000101 * jc) +
            sin(rad(3 * geomMeanAnom)) * 0.000289
        val sunTrueLong = geomMeanLong + sunEqCtr
        val sunAppLong = sunTrueLong - 0.00569 - 0.00478 * sin(rad(125.04 - 1934.136 * jc))

        val meanObliq = 23.0 + (26.0 + (21.448 - jc * (46.815 + jc * (0.00059 - jc * 0.001813))) / 60.0) / 60.0
        val obliqCorr = meanObliq + 0.00256 * cos(rad(125.04 - 1934.136 * jc))

        val sunDecl = deg(asin(sin(rad(obliqCorr)) * sin(rad(sunAppLong))))

        val y = tan(rad(obliqCorr / 2.0)).pow(2.0)
        val eqTime = 4.0 * deg(
            y * sin(2.0 * rad(geomMeanLong)) -
                2.0 * eccent * sin(rad(geomMeanAnom)) +
                4.0 * eccent * y * sin(rad(geomMeanAnom)) * cos(2.0 * rad(geomMeanLong)) -
                0.5 * y * y * sin(4.0 * rad(geomMeanLong)) -
                1.25 * eccent * eccent * sin(2.0 * rad(geomMeanAnom))
        )

        val minutes = minutesPastMidnightUtc(timeMillis)
        var trueSolarTime = (minutes + eqTime + 4.0 * longitude) % 1440.0
        if (trueSolarTime < 0) trueSolarTime += 1440.0

        var hourAngle = trueSolarTime / 4.0 - 180.0
        if (hourAngle < -180.0) hourAngle += 360.0

        val latR = rad(latitude)
        val decR = rad(sunDecl)
        val haR = rad(hourAngle)

        val cosZenith = sin(latR) * sin(decR) + cos(latR) * cos(decR) * cos(haR)
        val zenith = deg(acosClamped(cosZenith))
        val elevation = 90.0 - zenith

        val azDenom = cos(latR) * sin(rad(zenith))
        var azimuth = if (abs(azDenom) > 0.001) {
            var az = 180.0 - deg(
                acosClamped((sin(latR) * cos(rad(zenith)) - sin(decR)) / azDenom)
            )
            if (hourAngle > 0) az = -az
            az
        } else {
            if (latitude > 0) 180.0 else 0.0
        }
        if (azimuth < 0) azimuth += 360.0
        azimuth %= 360.0

        return Position(
            body = Body.SUN,
            azimuthDegrees = azimuth,
            elevationDegrees = elevation,
            illuminatedFraction = 1.0
        )
    }

    fun moonPosition(
        latitude: Double,
        longitude: Double,
        timeMillis: Long = System.currentTimeMillis()
    ): Position {
        val jd = julianDate(timeMillis)
        val t = (jd - 2451545.0) / 36525.0

        // Meeus low-precision geocentric ecliptic longitude / latitude / distance.
        val Lp = normalizeDeg(
            218.3164477 + 481267.88123421 * t -
                0.0015786 * t * t + t * t * t / 538841.0 - t * t * t * t / 65194000.0
        )
        val D = normalizeDeg(
            297.8501921 + 445267.1114034 * t -
                0.0018819 * t * t + t * t * t / 545868.0 - t * t * t * t / 113065000.0
        )
        val M = normalizeDeg(
            357.5291092 + 35999.0502909 * t -
                0.0001536 * t * t + t * t * t / 24490000.0
        )
        val Mp = normalizeDeg(
            134.9633964 + 477198.8675055 * t +
                0.0087414 * t * t + t * t * t / 69699.0 - t * t * t * t / 14712000.0
        )
        val F = normalizeDeg(
            93.2720950 + 483202.0175233 * t -
                0.0036539 * t * t - t * t * t / 3526000.0 + t * t * t * t / 863310000.0
        )

        val lonEcl = Lp +
            6.289 * sin(rad(Mp)) +
            1.274 * sin(rad(2 * D - Mp)) +
            0.658 * sin(rad(2 * D)) +
            0.214 * sin(rad(2 * Mp)) -
            0.186 * sin(rad(M)) -
            0.114 * sin(rad(2 * F))
        val latEcl =
            5.128 * sin(rad(F)) +
                0.281 * sin(rad(Mp + F)) +
                0.278 * sin(rad(Mp - F)) +
                0.173 * sin(rad(2 * D - F))
        val distEarthRadii =
            60.2666 -
                3.208 * cos(rad(Mp)) -
                0.580 * cos(rad(2 * D - Mp)) -
                0.460 * cos(rad(2 * D))

        val eclipticObliquity = 23.439291 - 0.0130042 * t
        val lonR = rad(lonEcl)
        val latRMoon = rad(latEcl)
        val oblR = rad(eclipticObliquity)

        val ra = atan2(
            sin(lonR) * cos(oblR) - tan(latRMoon) * sin(oblR),
            cos(lonR)
        )
        val dec = asin(sin(latRMoon) * cos(oblR) + cos(latRMoon) * sin(oblR) * sin(lonR))

        val gmst = normalizeDeg(
            280.46061837 + 360.98564736629 * (jd - 2451545.0) +
                0.000387933 * t * t - t * t * t / 38710000.0
        )
        val lst = normalizeDeg(gmst + longitude)
        val ha = rad(normalizeDeg(lst - deg(ra)))

        val latR = rad(latitude)
        val sinAlt = sin(latR) * sin(dec) + cos(latR) * cos(dec) * cos(ha)
        val elevation = deg(asin(sinAlt.coerceIn(-1.0, 1.0)))

        val yAz = -cos(dec) * sin(ha)
        val xAz = cos(latR) * sin(dec) - sin(latR) * cos(dec) * cos(ha)
        var azimuth = normalizeDeg(deg(atan2(yAz, xAz)))

        // Crude illuminated fraction from elongation (sun–moon angle).
        val sun = sunPosition(latitude, longitude, timeMillis)
        val elong = angularSeparationDegrees(
            sun.azimuthDegrees,
            sun.elevationDegrees,
            azimuth,
            elevation
        )
        val illuminated = ((1.0 + cos(rad(elong))) / 2.0).coerceIn(0.0, 1.0)

        // Parallax nudge for distance (small; keeps elevation usable near horizon).
        val parallax = deg(asin((1.0 / distEarthRadii).coerceIn(-1.0, 1.0)))
        val elevCorr = elevation - parallax * cos(rad(elevation))

        return Position(
            body = Body.MOON,
            azimuthDegrees = azimuth,
            elevationDegrees = elevCorr,
            illuminatedFraction = illuminated
        )
    }

    fun normalizeDegrees(degrees: Double): Double {
        var d = degrees % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /** Smallest signed delta a→b in (−180, 180]. */
    fun signedDeltaDegrees(fromDegrees: Double, toDegrees: Double): Double {
        var d = normalizeDegrees(toDegrees) - normalizeDegrees(fromDegrees)
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    const val MIN_ELEVATION_DEGREES = 5.0
    const val MIN_MOON_ILLUMINATION = 0.12

    private fun julianDate(timeMillis: Long): Double {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timeMillis
        }
        var y = cal.get(Calendar.YEAR)
        var m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH) +
            (cal.get(Calendar.HOUR_OF_DAY) +
                cal.get(Calendar.MINUTE) / 60.0 +
                cal.get(Calendar.SECOND) / 3600.0 +
                cal.get(Calendar.MILLISECOND) / 3_600_000.0) / 24.0
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    private fun minutesPastMidnightUtc(timeMillis: Long): Double {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = timeMillis
        }
        return cal.get(Calendar.HOUR_OF_DAY) * 60.0 +
            cal.get(Calendar.MINUTE) +
            cal.get(Calendar.SECOND) / 60.0 +
            cal.get(Calendar.MILLISECOND) / 60_000.0
    }

    private fun angularSeparationDegrees(
        az1: Double,
        el1: Double,
        az2: Double,
        el2: Double
    ): Double {
        val a1 = rad(az1)
        val a2 = rad(az2)
        val e1 = rad(el1)
        val e2 = rad(el2)
        val cosSep = sin(e1) * sin(e2) + cos(e1) * cos(e2) * cos(a1 - a2)
        return deg(acosClamped(cosSep))
    }

    private fun normalizeDeg(d: Double): Double = normalizeDegrees(d)
    private fun rad(d: Double): Double = d * PI / 180.0
    private fun deg(r: Double): Double = r * 180.0 / PI

    private fun acosClamped(x: Double): Double =
        kotlin.math.acos(x.coerceIn(-1.0, 1.0))
}
