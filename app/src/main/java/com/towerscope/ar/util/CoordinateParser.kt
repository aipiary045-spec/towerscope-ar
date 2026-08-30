package com.towerscope.ar.util

/**
 * Parses decimal latitude/longitude from field tech clipboard paste or manual entry.
 */
object CoordinateParser {

    data class ParsedCoordinates(
        val latitude: Double,
        val longitude: Double
    )

    sealed class ParseError(val message: String) {
        data object Empty : ParseError("Enter latitude and longitude")
        data object InvalidFormat : ParseError("Use decimal degrees, e.g. 30.2672, -97.7431")
        data class OutOfRange(val detail: String) : ParseError(detail)
    }

    fun parsePair(input: String): Result<ParsedCoordinates> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException(ParseError.Empty.message))

        val numbers = extractDecimalNumbers(trimmed)
        if (numbers.size >= 2) {
            return validate(numbers[0], numbers[1])
        }
        return Result.failure(IllegalArgumentException(ParseError.InvalidFormat.message))
    }

    fun parseFields(latitudeInput: String, longitudeInput: String): Result<ParsedCoordinates> {
        val latText = latitudeInput.trim()
        val lonText = longitudeInput.trim()
        if (latText.isEmpty() || lonText.isEmpty()) {
            return Result.failure(IllegalArgumentException(ParseError.Empty.message))
        }
        val lat = latText.toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException(ParseError.InvalidFormat.message))
        val lon = lonText.toDoubleOrNull()
            ?: return Result.failure(IllegalArgumentException(ParseError.InvalidFormat.message))
        return validate(lat, lon)
    }

    /**
     * Accepts a pair string or fills missing fields from a one-number paste.
     */
    fun parseClipboard(
        clipboardText: String,
        latitudeInput: String = "",
        longitudeInput: String = ""
    ): Result<ParsedCoordinates> {
        val trimmed = clipboardText.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException(ParseError.Empty.message))
        }

        val numbers = extractDecimalNumbers(trimmed)
        return when {
            numbers.size >= 2 -> validate(numbers[0], numbers[1])
            numbers.size == 1 && latitudeInput.isBlank() && longitudeInput.isBlank() ->
                Result.failure(IllegalArgumentException(ParseError.InvalidFormat.message))
            numbers.size == 1 && latitudeInput.isBlank() ->
                parseFields(numbers[0].toString(), longitudeInput)
            numbers.size == 1 && longitudeInput.isBlank() ->
                parseFields(latitudeInput, numbers[0].toString())
            else -> parsePair(trimmed)
        }
    }

    private fun validate(latitude: Double, longitude: Double): Result<ParsedCoordinates> {
        if (!latitude.isFinite() || !longitude.isFinite()) {
            return Result.failure(IllegalArgumentException(ParseError.InvalidFormat.message))
        }
        if (latitude !in -90.0..90.0) {
            return Result.failure(
                IllegalArgumentException(ParseError.OutOfRange("Latitude must be between -90 and 90").message)
            )
        }
        if (longitude !in -180.0..180.0) {
            return Result.failure(
                IllegalArgumentException(ParseError.OutOfRange("Longitude must be between -180 and 180").message)
            )
        }
        return Result.success(ParsedCoordinates(latitude, longitude))
    }

    private fun extractDecimalNumbers(input: String): List<Double> {
        val cleaned = input
            .replace(Regex("(?i)lat(?:itude)?\\s*[:=]?\\s*"), "")
            .replace(Regex("(?i)lon(?:g(?:itude)?)?\\s*[:=]?\\s*"), "")
            .replace(Regex("(?i)lng\\s*[:=]?\\s*"), "")
            .trim()
        return NUMBER_PATTERN.findAll(cleaned)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
    }

    private val NUMBER_PATTERN = Regex("-?\\d+(?:\\.\\d+)?")
}
