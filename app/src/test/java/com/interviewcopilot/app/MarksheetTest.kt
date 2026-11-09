package com.interviewcopilot.app

import com.interviewcopilot.app.core.EduCodec
import com.interviewcopilot.app.core.EduLevel
import com.interviewcopilot.app.core.EduRecord
import com.interviewcopilot.app.core.EduSummary
import com.interviewcopilot.app.core.MarksheetAi
import com.interviewcopilot.app.core.MarksheetParser
import com.interviewcopilot.app.core.OcrBox
import com.interviewcopilot.app.core.RowBuilder
import com.interviewcopilot.app.core.Subject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarksheetTest {
    private fun rows(text: String) = text.trim().lines()

    @Test
    fun cbseTenthIsSummedFromSubjectRows() {
        val r = MarksheetParser.parse(
            EduLevel.Tenth,
            rows(
                """
                CENTRAL BOARD OF SECONDARY EDUCATION
                SECONDARY SCHOOL EXAMINATION (CLASS X) 2021
                184 ENGLISH LNG & LIT. 095 NINETY FIVE A1
                085 HINDI COURSE-B 088 EIGHTY EIGHT A2
                041 MATHEMATICS STANDARD 092 NINETY TWO A1
                086 SCIENCE 090 NINETY A1
                087 SOCIAL SCIENCE 094 NINETY FOUR A1
                """,
            ),
        )
        assertEquals(5, r.subjects.size)
        assertEquals(459.0, r.obtained!!, 0.01)
        assertEquals(500.0, r.maxMarks!!, 0.01)
        assertEquals(91.8, r.percent()!!, 0.01)
        assertEquals("CBSE", r.institution)
    }

    @Test
    fun tamilNaduHscUsesTheTotalRow() {
        val r = MarksheetParser.parse(
            EduLevel.Twelfth,
            rows(
                """
                GOVERNMENT OF TAMIL NADU
                DEPARTMENT OF GOVERNMENT EXAMINATIONS
                HIGHER SECONDARY EXAMINATION, MARCH 2023
                TAMIL 092
                ENGLISH 088
                PHYSICS 095
                CHEMISTRY 090
                MATHEMATICS 100
                COMPUTER SCIENCE 099
                TOTAL 564
                """,
            ),
        )
        assertEquals(564.0, r.obtained!!, 0.01)
        assertEquals(600.0, r.maxMarks!!, 0.01)
        assertEquals(94.0, r.percent()!!, 0.01)
        assertEquals("Tamil Nadu State Board", r.institution)
        assertEquals("2023", r.year)
        assertEquals(6, r.subjects.size)
    }

    @Test
    fun explicitTotalOutOfIsUsed() {
        val r = MarksheetParser.parse(EduLevel.Tenth, rows("Total Marks Obtained : 437 / 500\nPercentage 87.4 %"))
        assertEquals(437.0, r.obtained!!, 0.01)
        assertEquals(500.0, r.maxMarks!!, 0.01)
        assertEquals(87.4, r.percent()!!, 0.01)
    }

    @Test
    fun printedPercentageAloneIsKept() {
        val r = MarksheetParser.parse(EduLevel.Twelfth, rows("Aggregate percentage 82.6%"))
        assertNull(r.obtained)
        assertEquals(82.6, r.percent()!!, 0.01)
    }

    @Test
    fun printedPercentageCorrectsAWrongMaximumGuess() {
        // 1050 of 1200 is 87.5 %, so the maximum must not be guessed as 1100.
        val r = MarksheetParser.parse(EduLevel.Twelfth, rows("GRAND TOTAL 1050\nPercentage 87.5"))
        assertEquals(1200.0, r.maxMarks!!, 0.01)
        assertEquals(87.5, r.percent()!!, 0.01)
    }

    @Test
    fun absentSubjectCountsAsZeroNotSkipped() {
        val subjects = MarksheetParser.findSubjects(rows("TAMIL 80\nENGLISH 75\nPHYSICS AB\nMATHEMATICS 90"))
        assertEquals(4, subjects.size)
        assertEquals(0.0, subjects[2].marks, 0.01)
    }

    @Test
    fun collegeCgpaIsRead() {
        val r = MarksheetParser.parse(
            EduLevel.College,
            rows(
                """
                ANNA UNIVERSITY, CHENNAI
                B.TECH COMPUTER SCIENCE AND BUSINESS SYSTEMS
                SEMESTER GPA : 8.52
                CGPA : 8.41
                """,
            ),
        )
        assertEquals(8.41, r.cgpa!!, 0.001)
        assertEquals("Anna University", r.institution)
        assertEquals(84.1, r.percent()!!, 0.01)
        assertTrue(r.percentIsEstimate())
    }

    @Test
    fun collegeSemesterGpasAreAveragedWhenNoCgpaIsPrinted() {
        val r = MarksheetParser.parse(EduLevel.College, rows("Semester 1 GPA 8.0\nSemester 2 GPA 9.0"))
        assertEquals(8.5, r.cgpa!!, 0.01)
    }

    @Test
    fun collegeGradeTableGivesACreditWeightedGpa() {
        val r = MarksheetParser.parse(
            EduLevel.College,
            rows(
                """
                CS3452 Theory of Computation 3 A+ PASS
                CS3491 Artificial Intelligence 3 A PASS
                CS3401 Algorithms 4 O PASS
                CS3451 Operating Systems 3 B+ PASS
                """,
            ),
        )
        // (3*9 + 3*8 + 4*10 + 3*7) / 13 = 112 / 13 = 8.615...
        assertEquals(8.62, r.cgpa!!, 0.01)
    }

    @Test
    fun nothingReadableGivesAnEmptyRecord() {
        val r = MarksheetParser.parse(EduLevel.Tenth, rows("lorem ipsum\nnothing here"))
        assertTrue(!r.hasData)
    }

    @Test
    fun rowBuilderKeepsNamesAndMarksOnTheSameLine() {
        val boxes = listOf(
            OcrBox("085", 400, 102, 450, 122),
            OcrBox("TAMIL", 20, 100, 120, 120),
            OcrBox("ENGLISH", 20, 140, 140, 160),
            OcrBox("092", 400, 141, 450, 161),
        )
        assertEquals(listOf("TAMIL  085", "ENGLISH  092"), RowBuilder.build(boxes))
    }

    @Test
    fun codecRoundTrips() {
        val r = EduRecord("CBSE", "2021", 459.0, 500.0, 91.8, null, listOf(Subject("ENGLISH", 95.0), Subject("HINDI", 88.5)), "scan")
        val back = EduCodec.decode(EduCodec.encode(r))
        assertEquals(r, back)
        assertNull(EduCodec.decode(""))
    }

    @Test
    fun aiReplyIsParsedAndValidated() {
        val ai = MarksheetAi.parseReply(
            """
            BOARD: Tamil Nadu State Board
            YEAR: 2023
            OBTAINED: 564
            MAX: 600
            PERCENTAGE: -
            CGPA: -
            SUBJECT: Tamil | 92
            SUBJECT: English | 88
            """,
        )
        assertNotNull(ai)
        assertEquals(564.0, ai!!.obtained!!, 0.01)
        assertEquals(2, ai.subjects.size)
        assertEquals(94.0, ai.percent()!!, 0.01)
    }

    @Test
    fun inconsistentAiReplyIsRejectedAndRuleReadingIsKept() {
        val base = EduRecord(obtained = 450.0, maxMarks = 500.0)
        val bad = MarksheetAi.parseReply("OBTAINED: 700\nMAX: 600")
        assertNull(bad)
        assertEquals(base, MarksheetAi.merge(base, bad))
    }

    @Test
    fun mergeFillsGapsFromTheRuleReading() {
        val base = EduRecord(institution = "CBSE", year = "2021")
        val ai = EduRecord(obtained = 450.0, maxMarks = 500.0, source = "ai")
        val m = MarksheetAi.merge(base, ai)
        assertEquals("CBSE", m.institution)
        assertEquals(90.0, m.percent()!!, 0.01)
    }

    @Test
    fun summaryLineForThePrompt() {
        val tenth = EduRecord(institution = "CBSE", year = "2021", obtained = 459.0, maxMarks = 500.0)
        val college = EduRecord(institution = "Anna University", cgpa = 8.4)
        val text = EduSummary.profile(mapOf(EduLevel.Tenth to tenth, EduLevel.College to college))
        assertEquals("10th: 91.8% (CBSE, 2021); College: CGPA 8.4 (Anna University)", text)
    }
}
