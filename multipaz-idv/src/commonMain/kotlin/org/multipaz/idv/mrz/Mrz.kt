package org.multipaz.idv.mrz

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Thrown when a machine-readable zone fails to parse or fails a check digit. */
class MrzException(message: String) : Exception(message)

/** The sex field of a TD3 MRZ, per ICAO 9303 Part 4. */
enum class MrzSex {
    /** The MRZ sex field was `M`. */
    MALE,

    /** The MRZ sex field was `F`. */
    FEMALE,

    /** The MRZ sex field was `<` or `X` (unspecified). */
    UNSPECIFIED
}

/**
 * The fields of a TD3 (passport) machine-readable zone, per ICAO 9303 Part 4.
 *
 * TD3 is the two-line, 44-characters-per-line format printed on the data page of a passport
 * booklet. It's also what's stored, byte-for-byte, in DG1 (see [org.multipaz.idv.lds.Lds]).
 *
 * @property documentCode the document code, e.g. `"P"` for a passport.
 * @property issuingState the issuing state or organization, as an alpha-3 code.
 * @property primaryIdentifier the primary identifier (usually the surname).
 * @property secondaryIdentifier the secondary identifier (usually the given names).
 * @property documentNumber the document number.
 * @property nationality the holder's nationality, as an alpha-3 code.
 * @property birthDate the holder's date of birth.
 * @property sex the holder's sex, per the MRZ field.
 * @property expiryDate the document's expiry date.
 * @property personalNumber the optional personal number field, or an empty string if unused.
 */
data class MrzTd3(
    val documentCode: String,
    val issuingState: String,
    val primaryIdentifier: String,
    val secondaryIdentifier: String,
    val documentNumber: String,
    val nationality: String,
    val birthDate: LocalDate,
    val sex: MrzSex,
    val expiryDate: LocalDate,
    val personalNumber: String,
) {
    /** The 2x44 character MRZ text this was parsed from, or that [Mrz.buildTd3] produced. */
    lateinit var raw: String
        internal set
}

/** TD3 (passport) machine-readable zone parsing, building and check digit computation. */
object Mrz {
    /** The length of each line of a TD3 MRZ. */
    const val LINE_LENGTH = 44
    private val CHECK_DIGIT_WEIGHTS = intArrayOf(7, 3, 1)

    /**
     * Computes the ICAO 9303 Part 3 check digit for a field.
     *
     * @param field the field, using `'<'` as the filler character.
     * @return the check digit, `'0'`-`'9'`.
     * @throws MrzException if [field] contains a character that isn't `0`-`9`, `A`-`Z` or `<`.
     */
    fun checkDigit(field: String): Char {
        var sum = 0
        for ((index, char) in field.withIndex()) {
            val value = when {
                char in '0'..'9' -> char - '0'
                char in 'A'..'Z' -> char - 'A' + 10
                char == '<' -> 0
                else -> throw MrzException("Invalid MRZ character '$char' in field \"$field\"")
            }
            sum += value * CHECK_DIGIT_WEIGHTS[index % 3]
        }
        return '0' + (sum % 10)
    }

    /**
     * Parses a TD3 machine-readable zone.
     *
     * @param mrz the 2x44 character MRZ, either as a single 88-character string or as two
     *   44-character lines separated by `\n`.
     * @param today used to resolve the two-digit years in the MRZ into full years: the MRZ itself
     *   carries no century, so this is inherently a heuristic (see [resolveBirthYear] and
     *   [resolveExpiryYear]). Defaults to [kotlinx.datetime.Clock.System]'s current date.
     * @throws MrzException if the MRZ is malformed or any check digit doesn't match.
     */
    fun parseTd3(mrz: String, today: LocalDate = todayUtc()): MrzTd3 {
        val lines = normalizeToLines(mrz)
        val line1 = lines.first
        val line2 = lines.second

        val documentCode = line1.substring(0, 2).trimEnd('<')
        val issuingState = line1.substring(2, 5)
        val (primaryIdentifier, secondaryIdentifier) = parseNameField(line1.substring(5, 44))

        val documentNumberField = line2.substring(0, 9)
        val documentNumberCheck = line2[9]
        val nationality = line2.substring(10, 13)
        val birthDateField = line2.substring(13, 19)
        val birthDateCheck = line2[19]
        val sexField = line2[20]
        val expiryDateField = line2.substring(21, 27)
        val expiryDateCheck = line2[27]
        val personalNumberField = line2.substring(28, 42)
        val personalNumberCheck = line2[42]
        val compositeCheck = line2[43]

        requireCheckDigit(documentNumberField, documentNumberCheck, "document number")
        requireCheckDigit(birthDateField, birthDateCheck, "date of birth")
        requireCheckDigit(expiryDateField, expiryDateCheck, "date of expiry")
        // Per ICAO 9303-4 Section 4.2.2, an all-filler optional data field has a filler (not
        // computed) check digit.
        if (personalNumberField != "<".repeat(14)) {
            requireCheckDigit(personalNumberField, personalNumberCheck, "personal number")
        }
        val compositeInput = documentNumberField + documentNumberCheck +
                birthDateField + birthDateCheck +
                expiryDateField + expiryDateCheck +
                personalNumberField + personalNumberCheck
        requireCheckDigit(compositeInput, compositeCheck, "composite")

        val sex = when (sexField) {
            'M' -> MrzSex.MALE
            'F' -> MrzSex.FEMALE
            '<', 'X' -> MrzSex.UNSPECIFIED
            else -> throw MrzException("Invalid sex field '$sexField'")
        }

        val result = MrzTd3(
            documentCode = documentCode,
            issuingState = issuingState,
            primaryIdentifier = primaryIdentifier,
            secondaryIdentifier = secondaryIdentifier,
            documentNumber = documentNumberField.trimEnd('<'),
            nationality = nationality,
            birthDate = parseYymmdd(birthDateField, resolveBirthYear(yearOfCentury(birthDateField), today)),
            sex = sex,
            expiryDate = parseYymmdd(expiryDateField, resolveExpiryYear(yearOfCentury(expiryDateField), today)),
            personalNumber = personalNumberField.trimEnd('<'),
        )
        result.raw = "$line1\n$line2"
        return result
    }

    /**
     * Builds a valid TD3 machine-readable zone from its fields, computing every check digit.
     *
     * @throws MrzException if a field is too long for its MRZ slot.
     */
    fun buildTd3(
        documentCode: String,
        issuingState: String,
        primaryIdentifier: String,
        secondaryIdentifier: String,
        documentNumber: String,
        nationality: String,
        birthDate: LocalDate,
        sex: MrzSex,
        expiryDate: LocalDate,
        personalNumber: String = "",
    ): MrzTd3 {
        val line1 = buildString {
            append(padField(documentCode, 2))
            append(padField(issuingState, 3))
            append(padField(formatNameField(primaryIdentifier, secondaryIdentifier), 39))
        }
        check(line1.length == LINE_LENGTH)

        val documentNumberField = padField(documentNumber, 9)
        val birthDateField = formatYymmdd(birthDate)
        val expiryDateField = formatYymmdd(expiryDate)
        val personalNumberField = padField(personalNumber, 14)
        val sexField = when (sex) {
            MrzSex.MALE -> "M"
            MrzSex.FEMALE -> "F"
            MrzSex.UNSPECIFIED -> "<"
        }
        val documentNumberCheck = checkDigit(documentNumberField)
        val birthDateCheck = checkDigit(birthDateField)
        val expiryDateCheck = checkDigit(expiryDateField)
        val personalNumberCheck = if (personalNumberField == "<".repeat(14)) {
            '<'
        } else {
            checkDigit(personalNumberField)
        }
        val compositeInput = documentNumberField + documentNumberCheck +
                birthDateField + birthDateCheck +
                expiryDateField + expiryDateCheck +
                personalNumberField + personalNumberCheck
        val compositeCheck = checkDigit(compositeInput)

        val line2 = buildString {
            append(documentNumberField)
            append(documentNumberCheck)
            append(padField(nationality, 3))
            append(birthDateField)
            append(birthDateCheck)
            append(sexField)
            append(expiryDateField)
            append(expiryDateCheck)
            append(personalNumberField)
            append(personalNumberCheck)
            append(compositeCheck)
        }
        check(line2.length == LINE_LENGTH)

        val result = MrzTd3(
            documentCode = documentCode,
            issuingState = issuingState,
            primaryIdentifier = primaryIdentifier,
            secondaryIdentifier = secondaryIdentifier,
            documentNumber = documentNumber,
            nationality = nationality,
            birthDate = birthDate,
            sex = sex,
            expiryDate = expiryDate,
            personalNumber = personalNumber,
        )
        result.raw = "$line1\n$line2"
        return result
    }

    /**
     * Resolves a two-digit birth year to a full year: the reading from the current century unless
     * that would place the birth date in the future, in which case the previous century (nobody is
     * born in the future).
     */
    fun resolveBirthYear(twoDigitYear: Int, today: LocalDate): Int {
        val currentCentury = (today.year / 100) * 100
        val candidate = currentCentury + twoDigitYear
        return if (candidate > today.year) candidate - 100 else candidate
    }

    /**
     * Resolves a two-digit expiry year to a full year: the reading from the current century,
     * unless that would place the expiry more than 20 years in the past, in which case the next
     * century (travel documents aren't issued with decades-past expiry dates).
     */
    fun resolveExpiryYear(twoDigitYear: Int, today: LocalDate): Int {
        val currentCentury = (today.year / 100) * 100
        val candidate = currentCentury + twoDigitYear
        return if (candidate < today.year - 20) candidate + 100 else candidate
    }

    private fun requireCheckDigit(field: String, expected: Char, name: String) {
        val actual = checkDigit(field)
        if (actual != expected) {
            throw MrzException("Check digit mismatch for $name: expected '$expected', computed '$actual'")
        }
    }

    private fun normalizeToLines(mrz: String): Pair<String, String> {
        val lines = mrz.split("\n").filter { it.isNotEmpty() }
        val (line1, line2) = when (lines.size) {
            2 -> Pair(lines[0], lines[1])
            1 -> {
                val single = lines[0]
                if (single.length != LINE_LENGTH * 2) {
                    throw MrzException("Expected ${LINE_LENGTH * 2} characters, got ${single.length}")
                }
                Pair(single.substring(0, LINE_LENGTH), single.substring(LINE_LENGTH))
            }
            else -> throw MrzException("Expected 1 or 2 lines, got ${lines.size}")
        }
        if (line1.length != LINE_LENGTH || line2.length != LINE_LENGTH) {
            throw MrzException("Expected $LINE_LENGTH characters per line")
        }
        return Pair(line1, line2)
    }

    private fun parseNameField(field: String): Pair<String, String> {
        val parts = field.split("<<", limit = 2)
        val primary = collapseNameComponent(parts[0])
        val secondary = if (parts.size > 1) collapseNameComponent(parts[1]) else ""
        return Pair(primary, secondary)
    }

    private fun collapseNameComponent(field: String): String =
        field.replace('<', ' ').trim().replace(Regex(" +"), " ")

    private fun formatNameField(primaryIdentifier: String, secondaryIdentifier: String): String {
        val primary = primaryIdentifier.uppercase().replace(" ", "<")
        val secondary = secondaryIdentifier.uppercase().replace(" ", "<")
        return if (secondary.isEmpty()) "$primary<<" else "$primary<<$secondary"
    }

    private fun padField(value: String, length: Int): String {
        val upper = value.uppercase()
        if (upper.length > length) {
            throw MrzException("Field \"$value\" is longer than $length characters")
        }
        return upper.padEnd(length, '<')
    }

    private fun yearOfCentury(yymmdd: String): Int = yymmdd.substring(0, 2).toInt()

    private fun parseYymmdd(yymmdd: String, fullYear: Int): LocalDate {
        val month = yymmdd.substring(2, 4).toInt()
        val day = yymmdd.substring(4, 6).toInt()
        return LocalDate(fullYear, month, day)
    }

    private fun formatYymmdd(date: LocalDate): String {
        val yy = date.year % 100
        return "${yy.pad2()}${date.month.number.pad2()}${date.day.pad2()}"
    }

    private fun Int.pad2(): String = toString().padStart(2, '0')

    private fun todayUtc(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.UTC).date
}
