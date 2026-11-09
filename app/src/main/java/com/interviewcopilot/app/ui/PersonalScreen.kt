package com.interviewcopilot.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.interviewcopilot.app.core.EduLevel
import com.interviewcopilot.app.core.EduRecord
import com.interviewcopilot.app.vm.CopilotVM

private fun num(d: Double?): String = when {
    d == null -> ""
    d % 1.0 == 0.0 -> d.toLong().toString()
    else -> d.toString()
}

/** Personal details: who you are, your 10th, 12th and college marks (read from the marksheets), résumé and vocabulary. */
@Composable
fun PersonalScreen(vm: CopilotVM) {
    LargeTitleScreen("Personal") {
        SectionHeader("About you")
        IosField(vm.g("name"), { vm.put("name", it) }, "Your name")
        Spacer(Modifier.height(8.dp))
        IosField(vm.g("about"), { vm.put("about", it) }, "One line about you, e.g. final-year CSBS student", singleLine = false, minLines = 2)

        SectionHeader("Education")
        EduLevel.entries.forEach { EduCard(vm, it) }
        Card {
            ListRow(
                "Double-check marks with AI",
                subtitle = "Sends the scanned text to your online AI key. Off: everything stays on the phone",
                divider = false,
                trailing = { IosSwitch(vm.b("eduAi")) { vm.put("eduAi", it.toString()) } },
            )
        }
        SectionFooter("Marks and percentages are used to answer questions about you correctly. You can correct any number by hand.")

        SectionHeader("Résumé")
        IosField(vm.g("resume"), { vm.put("resume", it) }, "Paste your résumé text", singleLine = false, minLines = 7)
        SectionFooter("Used to personalise answers. Long text is shortened to keep answers fast.")

        SectionHeader("Custom vocabulary")
        IosField(vm.g("vocab"), { vm.put("vocab", it) }, "spring boot=Spring Boot", singleLine = false, minLines = 4)
        SectionFooter("One rule per line, wrong=correct. Corrects transcription and helps Whisper spell your terms.")
    }
}

@Composable
private fun EduCard(vm: CopilotVM, level: EduLevel) {
    val c = LocalAppColors.current
    val record = vm.edu(level)
    val busy = vm.eduBusy[level.key] == true
    val message = vm.eduMessage[level.key].orEmpty()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importMarksheet(level, uris)
    }
    var showRaw by remember { mutableStateOf(false) }
    // The text boxes restart from the saved record only after a new scan, never while typing.
    var obtained by remember(message) { mutableStateOf(num(record?.obtained)) }
    var maxMarks by remember(message) { mutableStateOf(num(record?.maxMarks)) }
    var printed by remember(message) { mutableStateOf(num(record?.percentage)) }
    var cgpa by remember(message) { mutableStateOf(num(record?.cgpa)) }
    var board by remember(message) { mutableStateOf(record?.institution.orEmpty()) }
    var year by remember(message) { mutableStateOf(record?.year.orEmpty()) }

    fun edit(change: EduRecord.() -> EduRecord) = vm.saveEdu(level, (vm.edu(level) ?: EduRecord(source = "manual")).change())

    Card(Modifier.padding(bottom = 12.dp).animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(level.label, Type.headline)
                    Txt(level.hint, Type.footnote, c.text2)
                }
                val pct = record?.percent()
                if (pct != null) Pill("${"%.1f".format(pct)}%", c.success)
            }
            if (record != null && record.hasData) {
                val o = record.obtained
                val m = record.maxMarks
                val line = when {
                    o != null && m != null -> "${num(o)} out of ${num(m)} marks"

                    record.cgpa != null -> "CGPA ${num(
                        record.cgpa,
                    )}" + if (record.percentIsEstimate()) " · about ${"%.1f".format(record.percent())}% (CGPA × 10)" else ""

                    else -> "Printed percentage"
                }
                Txt(line, Type.body)
            }
            IosButton(
                if (busy) {
                    "Reading…"
                } else if (record?.hasData == true) {
                    "Upload again"
                } else {
                    "Upload marksheet (photo or PDF)"
                },
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Primary,
                enabled = !busy,
                icon = Sym.upload,
            ) { picker.launch(arrayOf("image/*", "application/pdf")) }
            if (message.isNotEmpty()) Txt(message, Type.footnote, c.text2)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IosField(
                    obtained,
                    {
                        obtained = it
                        edit { copy(obtained = it.toDoubleOrNull()) }
                    },
                    "Marks obtained",
                    Modifier.weight(1f),
                    fillColor = c.background,
                )
                IosField(
                    maxMarks,
                    {
                        maxMarks = it
                        edit { copy(maxMarks = it.toDoubleOrNull()) }
                    },
                    "Out of",
                    Modifier.weight(1f),
                    fillColor = c.background,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (level == EduLevel.College) {
                    IosField(
                        cgpa,
                        {
                            cgpa = it
                            edit { copy(cgpa = it.toDoubleOrNull()) }
                        },
                        "CGPA",
                        Modifier.weight(1f),
                        fillColor = c.background,
                    )
                } else {
                    IosField(
                        printed,
                        {
                            printed = it
                            edit { copy(percentage = it.toDoubleOrNull()) }
                        },
                        "Printed % (optional)",
                        Modifier.weight(1f),
                        fillColor = c.background,
                    )
                }
                IosField(
                    year,
                    {
                        year = it
                        edit { copy(year = it) }
                    },
                    "Year",
                    Modifier.weight(1f),
                    fillColor = c.background,
                )
            }
            IosField(
                board,
                {
                    board = it
                    edit { copy(institution = it) }
                },
                if (level == EduLevel.College) "University or college" else "Board",
                fillColor = c.background,
            )

            val subjects = record?.subjects.orEmpty()
            if (subjects.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt("Subjects read", Type.footnote, c.text2)
                    subjects.take(10).forEach { Txt("${it.name}  ·  ${num(it.marks)}", Type.subhead) }
                }
            }
            val raw = vm.eduRaw(level)
            if (raw.isNotBlank()) {
                Txt(
                    if (showRaw) "Hide scanned text" else "Show scanned text",
                    Type.subhead,
                    c.text,
                    Modifier.pressable {
                        showRaw = !showRaw
                    }.padding(vertical = 4.dp),
                )
                if (showRaw) Txt(raw, Type.footnote, c.text2)
            }
            if (record != null || raw.isNotBlank()) {
                Txt("Remove", Type.subhead, c.danger, Modifier.pressable { vm.clearEdu(level) }.padding(vertical = 4.dp))
            }
        }
    }
}
