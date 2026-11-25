package io.github.langkurst.ruru.ui

import androidx.compose.runtime.Composable
import io.github.langkurst.ruru.link.Words
import io.github.langkurst.ruru.ui.res.Res
import io.github.langkurst.ruru.ui.res.allStringResources
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The string resource a [Words] names (its key is the `name` in strings.xml). */
fun Words.resource(): StringResource =
    Res.allStringResources[key] ?: error("strings.xml has no string called $key")

/** The sentence in the person's language, for a screen. */
@Composable
fun Words.text(): String = stringResource(resource(), *args.toTypedArray())

/** The sentence in the person's language, from code that is not a screen (the tray, notifications). */
suspend fun Words.resolve(): String = getString(resource(), *args.toTypedArray())
