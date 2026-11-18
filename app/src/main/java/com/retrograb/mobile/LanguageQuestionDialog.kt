package com.retrograb.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun LanguageQuestionDialog(question: LanguageQuestion) {
    var selected by rememberSaveable(question.id) { mutableStateOf(question.defaultLanguage) }
    var useForRest by rememberSaveable(question.id) { mutableStateOf(false) }
    val skip = { LanguagePrompt.respond(question, null) }
    AlertDialog(
        onDismissRequest = skip,
        title = { Text("Choose language") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("This song is available in more than one language:")
                Text(question.label, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.padding(top = 8.dp).selectableGroup()) {
                    question.languages.forEach { language ->
                        val name = SpotifyLanguages.displayName(language)
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = selected == language,
                                onClick = { selected = language },
                                role = Role.RadioButton
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected == language, onClick = null)
                            Text(
                                if (language == question.spotifyLanguage) "$name (Spotify's version)" else name,
                                Modifier.padding(start = 12.dp)
                            )
                        }
                    }
                }
                if (question.multi) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                            value = useForRest,
                            onValueChange = { useForRest = it },
                            role = Role.Checkbox
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = useForRest, onCheckedChange = null)
                        Text("Use this language for the rest of this album / playlist", Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { LanguagePrompt.respond(question, LanguageAnswer(selected, useForRest)) }) {
                Text("Download")
            }
        },
        dismissButton = { TextButton(onClick = skip) { Text("Skip song") } }
    )
}
