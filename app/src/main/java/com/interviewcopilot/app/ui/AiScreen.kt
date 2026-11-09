package com.interviewcopilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.interviewcopilot.app.core.EngineOrder
import com.interviewcopilot.app.vm.CopilotVM

/** The AI tab: pick the answer engine, paste API keys, and test that each one works. */
@Composable
fun AiScreen(vm: CopilotVM, onOpenModels: () -> Unit) {
    val c = LocalAppColors.current
    LargeTitleScreen("AI") {
        SectionHeader("Answer engine")
        Card {
            EngineOrder.all.forEachIndexed { i, name ->
                ListRow(
                    EngineOrder.display(name),
                    subtitle = vm.engineStatus(name),
                    divider = i != EngineOrder.all.lastIndex,
                    onClick = { vm.selectEngine(name) },
                    trailing = { if (vm.selectedEngine == name) Sf(Sym.check, c.text, 20.dp) },
                )
            }
        }
        SectionFooter(
            "Auto tries Groq, then Google, then OpenRouter. If one fails or reaches its limit it is skipped for a while and the next one answers. " +
                "If every online engine fails, the on-device model answers.",
        )

        EngineKeyCard(vm, "Groq", EngineOrder.GROQ, "groqKey", "groqModel", "Groq API key (gsk_…)", "Model id, e.g. llama-3.3-70b-versatile")
        EngineKeyCard(vm, "Google (Gemini)", EngineOrder.GEMINI, "geminiKey", "geminiModel", "Google AI Studio API key", "Model id, e.g. gemini-3.1-flash-lite")
        EngineKeyCard(vm, "OpenRouter", EngineOrder.OPENROUTER, "orKey", "orModel", "OpenRouter API key", "Model id, e.g. meta-llama/llama-3.3-70b-instruct")

        SectionHeader("On this phone")
        Card {
            ListRow(
                "Local models",
                icon = Sym.cpu,
                iconBg = c.primary,
                chevron = true,
                divider = false,
                onClick = onOpenModels,
                subtitle = vm.engineStatus(EngineOrder.LOCAL),
            )
        }
        Spacer(Modifier.height(10.dp))
        Card {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt("Test local model", Type.body, modifier = Modifier.weight(1f))
                    IosButton("Test", kind = ButtonKind.Secondary, enabled = vm.engineReady(EngineOrder.LOCAL) && vm.testing[EngineOrder.LOCAL] != true) {
                        vm.testEngine(EngineOrder.LOCAL)
                    }
                }
                vm.testResults[EngineOrder.LOCAL]?.let { Txt(it, Type.footnote, c.text2, Modifier.padding(top = 6.dp)) }
            }
        }
        SectionFooter(
            "Keys are stored encrypted on this device. Questions go to an online engine only when it is the one answering. " +
                "Groq is usually the quickest: answers start in well under a second.",
        )
    }
}

@Composable
private fun EngineKeyCard(vm: CopilotVM, title: String, engine: String, keyPref: String, modelPref: String, keyHint: String, modelHint: String) {
    val c = LocalAppColors.current
    SectionHeader(title)
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            IosField(vm.g(keyPref), { vm.put(keyPref, it.trim()) }, keyHint, secret = true, fillColor = c.background)
            IosField(vm.g(modelPref), { vm.put(modelPref, it.trim()) }, modelHint, fillColor = c.background)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Txt(
                    vm.testResults[engine] ?: vm.engineStatus(engine),
                    Type.footnote,
                    c.text2,
                    Modifier.weight(1f).padding(end = 8.dp),
                    maxLines = 3,
                )
                IosButton("Test", kind = ButtonKind.Secondary, enabled = vm.g(keyPref).isNotBlank() && vm.testing[engine] != true) {
                    vm.testEngine(engine)
                }
            }
        }
    }
}
