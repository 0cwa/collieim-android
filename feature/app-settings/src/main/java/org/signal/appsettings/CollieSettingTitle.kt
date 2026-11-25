/*
 * Copyright 2026 Collie contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle

/** Marks a Collie-added setting title displayed outside the Collie settings screen. */
fun String.asCollieSettingTitle(): AnnotatedString =
  AnnotatedString(this, spanStyle = SpanStyle(fontStyle = FontStyle.Italic))
