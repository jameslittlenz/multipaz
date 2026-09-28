package org.multipaz.samples.validatopia.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The standard Validatopia screen: a top app bar whose title is a heading for TalkBack, an
 * optional labelled back button, and content that scrolls so nothing clips at 200% font scale.
 *
 * @param title the screen title.
 * @param onBack called by the back button, or `null` for a top-level screen.
 * @param actions app bar actions.
 * @param scrollable whether to wrap [content] in a vertical scroll (turn off for lazy lists).
 * @param content the screen body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValidatopiaScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = title, modifier = Modifier.semantics { heading() })
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = actions,
            )
        }
    ) { innerPadding ->
        val base = Modifier.fillMaxSize().padding(innerPadding)
        Column(
            modifier = (if (scrollable) base.verticalScroll(rememberScrollState()) else base)
                .fillMaxWidth()
                .padding(ContentPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** Padding for screen content. */
val ContentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** A section heading, exposed to TalkBack as a heading. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.semantics { heading() },
    )
}
