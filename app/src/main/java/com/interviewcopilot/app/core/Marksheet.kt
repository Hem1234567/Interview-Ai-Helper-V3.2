package com.interviewcopilot.app.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The three marksheets the Personal page asks for. [key] is used in storage. */
enum class EduLevel(val key: String, val label: String, val hint: String) {
    Tenth("10th", "10th marksheet", "SSLC, Matriculation, CBSE or ICSE"),
    Twelfth("12th", "12th marksheet", "HSC, CBSE or ISC"),
    College("college", "College marksheet", "Semester or consolidated grade sheet"),
}

data class Subject(val name: String, val marks: Double)

/** What was read from one marksheet (and can be corrected by hand). */
data class EduRecord(
    val institution: String = "",
    val year: String = "",
    val obtained: Double? = null,
    val maxMarks: Double? = null,
    val percentage: Double? = null,
    val cgpa: Double? = null,
    val subjects: List<Subject> = emptyList(),
    /** "scan", "ai" or "manual". */
    val source: String = "",
) {
    /** The percentage the app uses: marks first, then a printed percentage, then CGPA x 10. */
    fun percent(): Double? {
        val o = obtained
        val m = maxMarks
        return when {
            o != null && m != null && m > 0 -> round1(o / m * 100)
            percentage != null -> round1(percentage)
            cgpa != null -> round1(cgpa * 10)
            else -> null
        }
    }

    /** True when [percent] had to be estimated from the CGPA. */
    fun percentIsEstimate(): Boolean = (obtained == null || maxMarks == null) && percentage == null && cgpa != null

    val hasData: Boolean get() = obtained != null || percentage != null || cgpa != null
}

fun round1(v: Double): Double = (v * 10.0).roundToInt() / 10.0

/** Compact text form so a record fits in one stored string. */
object EduCodec {
    private fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    fun encode(r: EduRecord): String {
        val sb = StringBuilder()

        fun put(k: String, v: String) {
            if (v.isNotEmpty()) sb.append(k).append('=').append(v.replace('\n', ' ').replace('\r', ' ')).append('\n')
        }
        put("inst", r.institution)
        put("year", r.year)
        r.obtained?.let { put("obt", fmt(it)) }
        r.maxMarks?.let { put("max", fmt(it)) }
        r.percentage?.let { put("pct", fmt(it)) }
        r.cgpa?.let { put("cgpa", fmt(it)) }
        put("src", r.source)
        r.subjects.forEach { put("sub", it.name.replace('|', ' ') + "|" + fmt(it.marks)) }
        return sb.toString()
    }

    fun decode(s: String): EduRecord? {
        if (s.isBlank()) return null
        var r = EduRecord()
        val subjects = ArrayList<Subject>()
        for (line in s.lines()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.substring(0, i)
            val v = line.substring(i + 1)
            when (k) {
                "inst" -> {
                    r = r.copy(institution = v)
                }

                "year" -> {
                    r = r.copy(year = v)
                }

                "obt" -> {
                    r = r.copy(obtained = v.toDoubleOrNull())
                }

                "max" -> {
                    r = r.copy(maxMarks = v.toDoubleOrNull())
                }

                "pct" -> {
                    r = r.copy(percentage = v.toDoubleOrNull())
                }

                "cgpa" -> {
                    r = r.copy(cgpa = v.toDoubleOrNull())
                }

                "src" -> {
                    r = r.copy(source = v)
                }

                "sub" -> {
                    val name = v.substringBeforeLast('|')
                    val marks = v.substringAfterLast('|', "").toDoubleOrNull()
                    if (marks != null) subjects.add(Subject(name, marks))
                }
            }
        }
        return r.copy(subjects = subjects)
    }
}

/** One piece of recognised text with its position in the picture. */
data class OcrBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = max(1, bottom - top)
}

/**
 * Text recognisers return a table as separate blocks (one per column). This puts the pieces back into
 * rows by their vertical position so a subject name stays on the same line as its marks.
 */
object RowBuilder {
    fun build(boxes: List<OcrBox>): List<String> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.filter { it.text.isNotBlank() }.sortedBy { it.centerY }
        val rows = ArrayList<MutableList<OcrBox>>()
        for (b in sorted) {
            val row = rows.lastOrNull()
            if (row != null) {
                val avgY = row.sumOf { it.centerY } / row.size
                val avgH = row.sumOf { it.height } / row.size
                val tolerance = max(4, (min(avgH, b.height) * 0.6).toInt())
                if (kotlin.math.abs(b.centerY - avgY) <= tolerance) {
                    row.add(b)
                    continue
                }
            }
            rows.add(mutableListOf(b))
        }
        return rows.map { r -> r.sortedBy { it.left }.joinToString("  ") { it.text.trim() } }
    }
}

object MarksheetParser {
    private val subjectWords = listOf(
        "tamil",
        "english",
        "hindi",
        "french",
        "telugu",
        "malayalam",
        "kannada",
        "sanskrit",
        "urdu",
        "marathi",
        "bengali",
        "gujarati",
        "mathematics",
        "maths",
        "math",
        "applied mathematics",
        "science",
        "social science",
        "social studies",
        "history",
        "geography",
        "civics",
        "economics",
        "commerce",
        "accountancy",
        "accounts",
        "business studies",
        "business mathematics",
        "physics",
        "chemistry",
        "biology",
        "botany",
        "zoology",
        "bio-botany",
        "bio-zoology",
        "computer science",
        "computer applications",
        "computer technology",
        "informatics practices",
        "information technology",
        "biochemistry",
        "statistics",
        "political science",
        "physical education",
        "environmental science",
        "nutrition",
        "home science",
        "engineering graphics",
        "electronics",
        "general studies",
    )
    private val subjectRegex = subjectWords.associateWith { Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE) }
    private val numRegex = Regex("""\d+(?:\.\d+)?""")
    private val yearRegex = Regex("""(?<!\d)((?:19|20)\d{2})(?!\d)""")
    private val months = Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b""", RegexOption.IGNORE_CASE)

    private fun normalise(row: String): String {
        var s = row.replace('\u00A0', ' ').replace('|', ' ')
        s = s.replace(Regex("""(?<=\d)[Oo](?=\d)"""), "0").replace(Regex("""(?<=\d)[lI](?=\d)"""), "1")
        s = s.replace(Regex("""\b[Oo](?=\d{2}\b)"""), "0")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    fun parse(level: EduLevel, rawRows: List<String>, maxYear: Int = 2035): EduRecord {
        val rows = rawRows.flatMap { it.lines() }.map { normalise(it) }.filter { it.isNotBlank() }
        if (rows.isEmpty()) return EduRecord(source = "scan")
        val institution = detectInstitution(level, rows)
        val year = detectYear(rows, maxYear)
        val base = if (level == EduLevel.College) parseCollege(rows) else parseSchool(level, rows, institution)
        return base.copy(institution = institution, year = year, source = "scan")
    }

    // ---------- school ----------

    private fun parseSchool(level: EduLevel, rows: List<String>, institution: String): EduRecord {
        val subjects = findSubjects(rows)
        val printed = findPrintedPercent(rows)
        val total = findTotal(rows)
        var obtained: Double? = null
        var maxMarks: Double? = null
        if (total != null) {
            obtained = total.first
            maxMarks = total.second
        } else if (subjects.size >= 3) {
            obtained = subjects.sumOf { it.marks }
        }
        if (obtained != null && maxMarks == null) {
            maxMarks = inferMax(level, obtained, subjects.size, institution)
            // A printed percentage tells us the real maximum when our guess does not agree with it.
            if (printed != null && printed > 0) {
                val implied = obtained * 100.0 / printed
                val rounded = (implied / 50.0).roundToInt() * 50.0
                if (rounded in 200.0..1300.0 && kotlin.math.abs(rounded - maxMarks) > 50.0) maxMarks = rounded
            }
        }
        if (obtained != null && maxMarks != null && obtained > maxMarks) {
            obtained = null
            maxMarks = null
        }
        return EduRecord(obtained = obtained, maxMarks = maxMarks, percentage = printed, subjects = subjects)
    }

    private fun inferMax(level: EduLevel, total: Double, subjectCount: Int, institution: String): Double {
        if (subjectCount in 4..8 && total <= subjectCount * 100.0) return subjectCount * 100.0
        val b = institution.lowercase()
        val candidates = when {
            level == EduLevel.Tenth -> listOf(500.0, 600.0, 1000.0)
            b.contains("tamil nadu") -> listOf(600.0, 1200.0)
            b.contains("cbse") || b.contains("isc") -> listOf(500.0, 600.0)
            else -> listOf(500.0, 600.0, 1100.0, 1200.0)
        }
        return candidates.sorted().firstOrNull { total <= it } ?: (ceil(total / 100.0) * 100.0)
    }

    private val gradeToken = Regex("""^(?:[ABCDEO][12+]?|AB|ABS|ABSENT|PASS|P|F|FAIL)$""", RegexOption.IGNORE_CASE)

    /** Reads rows such as "TAMIL 085 EIGHTY FIVE A1" or "184 ENGLISH 095" and returns the marks of each subject. */
    fun findSubjects(rows: List<String>): List<Subject> {
        val out = LinkedHashMap<String, Subject>()
        for (row in rows) {
            val lower = row.lowercase()
            if (lower.contains("maximum") || lower.contains("total")) continue
            val hit = subjectWords.firstOrNull { subjectRegex.getValue(it).containsMatchIn(row) } ?: continue
            val nameMatch = Regex("""[A-Za-z][A-Za-z &().'-]*[A-Za-z]|[A-Za-z]""").find(row) ?: continue
            val name = nameMatch.value.trim().take(40).ifBlank { hit }
            val absent = Regex("""\bAB(?:S|SENT)?\b""").containsMatchIn(row)
            val nums = numRegex.findAll(row).map { it.value }.toMutableList()
            val nameAt = row.indexOf(nameMatch.value)
            if (nums.size >= 2 && nums[0].length == 3 && row.indexOf(nums[0]) < nameAt + nameMatch.value.length) nums.removeAt(0)
            if (nums.size >= 2 && nums[0].length == 3 && (nums[0].toDoubleOrNull() ?: 0.0) > 100.0) nums.removeAt(0)
            val values = nums.mapNotNull { it.toDoubleOrNull() }.filter { it in 0.0..100.0 }
            val marks = when {
                values.isNotEmpty() -> values.max()
                absent -> 0.0
                else -> continue
            }
            out.putIfAbsent(name.lowercase(), Subject(name, marks))
        }
        return out.values.toList()
    }

    private val percentLabel = Regex("""(?i)(?:percentage|per\s*cent|percent|aggregate)""")

    fun findPrintedPercent(rows: List<String>): Double? {
        for ((i, row) in rows.withIndex()) {
            if (percentLabel.containsMatchIn(row)) {
                val after = row.substring(percentLabel.find(row)!!.range.last + 1)
                val v = Regex("""\d{2,3}(?:\.\d{1,2})?""").find(after)?.value?.toDoubleOrNull()
                if (v != null && v in 20.0..100.0) return v
                val next = rows.getOrNull(i + 1)?.let { Regex("""^\D{0,6}(\d{2,3}(?:\.\d{1,2})?)""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
                if (next != null && next in 20.0..100.0) return next
            }
            val sign = Regex("""(\d{2,3}(?:\.\d{1,2})?)\s*%""").find(row)?.groupValues?.get(1)?.toDoubleOrNull()
            if (sign != null && sign in 20.0..100.0) return sign
        }
        return null
    }

    private val totalLabels = listOf(
        Regex("""(?i)\bgrand\s*total\b""") to 3,
        Regex("""(?i)\btotal\s*marks?(?:\s*obtained)?\b|\bmarks?\s*obtained\b|\btotal\s*obtained\b|\baggregate\s*marks?\b""") to 2,
        Regex("""(?i)\btotal\b""") to 1,
    )

    /** Returns (obtained, maximum or null) from a row such as "GRAND TOTAL 487" or "Total Marks 487 / 500". */
    fun findTotal(rows: List<String>): Pair<Double, Double?>? {
        var best: Triple<Int, Double, Double?>? = null
        for (row in rows) {
            val priority = totalLabels.firstOrNull { it.first.containsMatchIn(row) }?.second ?: continue
            if (Regex("""(?i)\bmaximum\b|\bmax\b""").containsMatchIn(row) && !row.contains('/')) continue
            val pair = Regex("""(\d{2,4})\s*(?:/|out\s*of|of)\s*(\d{3,4})""", RegexOption.IGNORE_CASE).find(row)
            val candidate: Pair<Double, Double?>? = if (pair != null) {
                val a = pair.groupValues[1].toDouble()
                val b = pair.groupValues[2].toDouble()
                if (b >= 200.0 && a in 20.0..b) a to b else null
            } else {
                val nums = numRegex.findAll(row).mapNotNull { it.value.toDoubleOrNull() }.filter { it in 101.0..1300.0 }.toList()
                when {
                    nums.isEmpty() -> null
                    nums.size == 1 -> nums[0] to null
                    nums.size == 2 && nums[1] >= nums[0] && nums[1] % 50.0 == 0.0 -> nums[0] to nums[1]
                    else -> nums.max() to null
                }
            }
            if (candidate != null && (best == null || priority > best.first)) best = Triple(priority, candidate.first, candidate.second)
        }
        return best?.let { it.second to it.third }
    }

    // ---------- college ----------

    private val cgpaRegex = Regex("""(?i)\bc\.?\s?g\.?\s?p\.?\s?a\b[^\d\n]{0,28}(\d{1,2}(?:\.\d{1,3})?)""")
    private val cumulativeRegex = Regex("""(?i)cumulative[^\d\n]{0,45}(\d{1,2}\.\d{1,3})""")
    private val gpaRegex = Regex("""(?i)(?<![a-z.])g\.?p\.?a\b[^\d\n]{0,18}(\d{1,2}\.\d{1,3})""")

    private fun valid(v: Double?): Double? = v?.takeIf { it > 0.0 && it <= 10.0 }

    private fun parseCollege(rows: List<String>): EduRecord {
        var cgpa: Double? = null
        for ((i, row) in rows.withIndex()) {
            val hit = cgpaRegex.find(row)?.groupValues?.get(1)?.toDoubleOrNull() ?: cumulativeRegex.find(row)?.groupValues?.get(1)?.toDoubleOrNull()
            val v = valid(hit)
            if (v != null) {
                cgpa = v // the last one printed is the overall figure
            } else if (cgpaRegex.containsMatchIn(row) || Regex("""(?i)\bcgpa\b""").containsMatchIn(row)) {
                val next = rows.getOrNull(i + 1)?.let { Regex("""\d{1,2}\.\d{1,3}""").find(it)?.value?.toDoubleOrNull() }
                valid(next)?.let { cgpa = it }
            }
        }
        var source = "scan"
        if (cgpa == null) {
            val gpas = rows.mapNotNull { r -> gpaRegex.find(r)?.groupValues?.get(1)?.toDoubleOrNull() }.mapNotNull { valid(it) }
            if (gpas.size >= 2) {
                cgpa = round2(gpas.average())
            } else if (gpas.size == 1) {
                cgpa = gpas[0]
            }
        }
        if (cgpa == null) cgpa = gradeTableGpa(rows)?.also { source = "scan" }
        val printed = findPrintedPercent(rows)
        val total = findTotal(rows)
        var obtained: Double? = null
        var maxMarks: Double? = null
        if (total != null && total.second != null) {
            obtained = total.first
            maxMarks = total.second
        }
        return EduRecord(obtained = obtained, maxMarks = maxMarks, percentage = printed, cgpa = cgpa, source = source)
    }

    private fun round2(v: Double): Double = (v * 100.0).roundToInt() / 100.0

    private val gradePoints =
        mapOf("O" to 10.0, "A+" to 9.0, "A" to 8.0, "B+" to 7.0, "B" to 6.0, "C" to 5.0, "U" to 0.0, "RA" to 0.0, "SA" to 0.0, "UA" to 0.0, "W" to 0.0)
    private val courseCode = Regex("""\b[A-Z]{2,4}\s?\d{3,5}[A-Z]?\b""")

    /** Credit-weighted grade point average from rows such as "CS3452 Theory of Computation 3 A+ P". Needs at least 4 courses. */
    private fun gradeTableGpa(rows: List<String>): Double? {
        var credits = 0.0
        var points = 0.0
        var courses = 0
        for (row in rows) {
            val code = courseCode.find(row) ?: continue
            val rest = row.substring(code.range.last + 1)
            val tokens = rest.split(' ').filter { it.isNotBlank() }
            val grade = tokens.lastOrNull { gradePoints.containsKey(it.uppercase()) } ?: continue
            val credit = tokens.firstOrNull { Regex("""^[1-6](?:\.\d)?$""").matches(it) }?.toDoubleOrNull() ?: continue
            credits += credit
            points += credit * gradePoints.getValue(grade.uppercase())
            courses++
        }
        return if (courses >= 4 && credits > 0) valid(round2(points / credits)) else null
    }

    // ---------- institution and year ----------

    fun detectInstitution(level: EduLevel, rows: List<String>): String {
        val text = rows.joinToString(" ").lowercase()
        return when {
            text.contains("central board of secondary education") || Regex("""\bcbse\b""").containsMatchIn(text) -> {
                "CBSE"
            }

            text.contains("council for the indian school certificate") -> {
                if (level == EduLevel.Twelfth) "ISC" else "ICSE"
            }

            level != EduLevel.College && text.contains("tamil nadu") -> {
                "Tamil Nadu State Board"
            }

            text.contains("anna university") -> {
                "Anna University"
            }

            else -> {
                val line = rows.firstOrNull { r ->
                    val l = r.lowercase()
                    (l.contains("board") || l.contains("university") || l.contains("autonomous")) && r.length in 8..70 && !l.contains("roll")
                }
                line?.replace(Regex("""[^A-Za-z&.,' -]"""), " ")?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
            }
        }
    }

    fun detectYear(rows: List<String>, maxYear: Int): String {
        val labelled = Regex("""(?i)year of (?:passing|examination)|month and year|examination held|exam held|held in|passed in|session""")
        for ((i, row) in rows.withIndex()) {
            if (labelled.containsMatchIn(row)) {
                val y = yearRegex.find(row)?.groupValues?.get(1) ?: rows.getOrNull(i + 1)?.let { yearRegex.find(it)?.groupValues?.get(1) }
                if (y != null && y.toInt() in 1990..maxYear) return y
            }
        }
        for (row in rows) {
            if (months.containsMatchIn(row)) {
                val y = yearRegex.findAll(row).map { it.groupValues[1].toInt() }.filter { it in 1990..maxYear }.maxOrNull()
                if (y != null) return y.toString()
            }
        }
        for (row in rows) {
            if (Regex("""(?i)\bexam""").containsMatchIn(row)) {
                val y = yearRegex.findAll(row).map { it.groupValues[1].toInt() }.filter { it in 1990..maxYear }.maxOrNull()
                if (y != null) return y.toString()
            }
        }
        return ""
    }
}

/** Asks an AI engine to read the scanned text, and merges its answer with the rule-based reading. */
object MarksheetAi {
    const val SYSTEM =
        "You read text that was scanned (OCR) from an Indian school or college marksheet and extract the marks. " +
            "Reply ONLY with these lines, nothing else. Use - when a value is not on the sheet. Never guess.\n" +
            "BOARD: board or university name\n" +
            "YEAR: year of passing\n" +
            "OBTAINED: total marks obtained (number)\n" +
            "MAX: total maximum marks (number)\n" +
            "PERCENTAGE: percentage if printed (number)\n" +
            "CGPA: cumulative CGPA if printed (number)\n" +
            "SUBJECT: subject name | marks obtained (one line per subject)"

    fun userPrompt(level: EduLevel, rows: List<String>): String =
        "This is the ${level.label} (${level.hint}). Scanned rows:\n" + rows.joinToString("\n").take(3500)

    private fun number(v: String): Double? {
        if (v.isBlank() || v.trim() == "-") return null
        return Regex("""\d+(?:\.\d+)?""").find(v)?.value?.toDoubleOrNull()
    }

    fun parseReply(reply: String): EduRecord? {
        var r = EduRecord(source = "ai")
        val subjects = ArrayList<Subject>()
        for (line in reply.lines()) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val key = line.substring(0, i).trim().uppercase().removePrefix("*").removePrefix("-").trim()
            val value = line.substring(i + 1).trim().trim('*').trim()
            when (key) {
                "BOARD" -> {
                    if (value != "-") r = r.copy(institution = value.take(60))
                }

                "YEAR" -> {
                    Regex("""(?:19|20)\d{2}""").find(value)?.value?.let { r = r.copy(year = it) }
                }

                "OBTAINED" -> {
                    number(value)?.let { r = r.copy(obtained = it) }
                }

                "MAX" -> {
                    number(value)?.let { r = r.copy(maxMarks = it) }
                }

                "PERCENTAGE" -> {
                    number(value)?.takeIf { it in 1.0..100.0 }?.let { r = r.copy(percentage = it) }
                }

                "CGPA" -> {
                    number(value)?.takeIf { it > 0.0 && it <= 10.0 }?.let { r = r.copy(cgpa = it) }
                }

                "SUBJECT" -> {
                    val name = value.substringBefore('|').trim()
                    val marks = number(value.substringAfter('|', ""))
                    if (name.isNotEmpty() && marks != null && marks <= 100.0) subjects.add(Subject(name.take(40), marks))
                }
            }
        }
        val o = r.obtained
        val m = r.maxMarks
        if (o != null && m != null && o > m) r = r.copy(obtained = null, maxMarks = null)
        r = r.copy(subjects = subjects)
        return if (r.hasData) r else null
    }

    /** The AI reading wins when it is complete and consistent; the rule-based reading fills any gaps. */
    fun merge(base: EduRecord, ai: EduRecord?): EduRecord {
        if (ai == null) return base
        val aiMarks = ai.obtained != null && ai.maxMarks != null
        val consistent = !aiMarks || ai.percentage == null || kotlin.math.abs((ai.percent() ?: 0.0) - ai.percentage) <= 1.5
        val trust = (aiMarks || ai.cgpa != null) && consistent
        if (!trust) return base
        return EduRecord(
            institution = ai.institution.ifBlank { base.institution },
            year = ai.year.ifBlank { base.year },
            obtained = ai.obtained ?: base.obtained,
            maxMarks = ai.maxMarks ?: base.maxMarks,
            percentage = ai.percentage ?: base.percentage,
            cgpa = ai.cgpa ?: base.cgpa,
            subjects = if (ai.subjects.size >= base.subjects.size) ai.subjects else base.subjects,
            source = "ai",
        )
    }
}

/** One short line per marksheet, for the answer prompt. */
object EduSummary {
    fun line(level: EduLevel, r: EduRecord?): String? {
        if (r == null || !r.hasData) return null
        val label = when (level) {
            EduLevel.Tenth -> "10th"
            EduLevel.Twelfth -> "12th"
            EduLevel.College -> "College"
        }
        val details = listOf(r.institution, r.year).filter { it.isNotBlank() }.joinToString(", ")
        val main = if (level == EduLevel.College && r.cgpa != null) {
            "CGPA ${r.cgpa}"
        } else {
            r.percent()?.let { "$it%" } ?: return null
        }
        return if (details.isEmpty()) "$label: $main" else "$label: $main ($details)"
    }

    fun profile(records: Map<EduLevel, EduRecord?>): String =
        EduLevel.entries.mapNotNull { line(it, records[it]) }.joinToString("; ")
}
