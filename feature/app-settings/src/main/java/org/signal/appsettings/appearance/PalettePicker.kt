/*
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.appsettings.appearance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.signal.appsettings.R
import org.signal.appsettings.asCollieSettingTitle
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.UserPalettes
import org.signal.core.ui.compose.theme.paletteSwatch

/**
 * Collie: the app color choice in Appearance. Each option previews its actual roles at the current brightness: the
 * surface as the disc, primary on top, secondary container and tertiary below. The selected option also carries a check
 * and a heavier label, so the choice reads without color. Options wrap, so large font sizes never clip a name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PalettePicker(
  paletteIds: List<String>,
  selectedId: String,
  onSelected: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  val gutter = dimensionResource(org.signal.core.ui.R.dimen.gutter)
  val isDark = isSystemInDarkTheme()

  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(top = 16.dp, bottom = 8.dp)
  ) {
    Text(
      text = stringResource(R.string.preferences_appearance__color).asCollieSettingTitle(),
      style = MaterialTheme.typography.bodyLarge,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(horizontal = gutter)
    )

    Text(
      text = paletteName(selectedId),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = gutter)
    )

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = gutter - 8.dp, end = gutter - 8.dp, top = 8.dp)
        .selectableGroup()
    ) {
      paletteIds.forEach { id ->
        PaletteOption(
          name = paletteName(id),
          swatch = paletteSwatch(id, isDark),
          selected = id == selectedId,
          onClick = { onSelected(id) },
          modifier = Modifier.testTag(AppearanceSettingsTestTags.paletteOption(id))
        )
      }
    }
  }
}

@Composable
private fun PaletteOption(
  name: String,
  swatch: List<Color>,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .widthIn(min = 76.dp, max = 112.dp)
      .clip(RoundedCornerShape(16.dp))
      .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
      .padding(horizontal = 4.dp, vertical = 8.dp)
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier
        .size(64.dp)
        .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
    ) {
      PaletteSwatch(swatch = swatch, modifier = Modifier.size(52.dp))

      if (selected) {
        Box(
          contentAlignment = Alignment.Center,
          modifier = Modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .border(1.5.dp, MaterialTheme.colorScheme.onPrimary, CircleShape)
        ) {
          Icon(
            imageVector = SignalIcons.Check.imageVector,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(16.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(6.dp))

    Text(
      text = name,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center
    )
  }
}

/** Surface disc with primary over the top half and secondary container and tertiary below, outlined for contrast. */
@Composable
private fun PaletteSwatch(swatch: List<Color>, modifier: Modifier = Modifier) {
  val (primary, secondaryContainer, tertiary, surface) = swatch
  val outline = MaterialTheme.colorScheme.outlineVariant

  Canvas(modifier = modifier) {
    val radius = size.minDimension / 2
    val inner = radius * 0.72f
    val topLeft = Offset(center.x - inner, center.y - inner)
    val arcSize = Size(inner * 2, inner * 2)

    drawCircle(color = surface, radius = radius)
    drawArc(color = primary, startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = topLeft, size = arcSize)
    drawArc(color = secondaryContainer, startAngle = 90f, sweepAngle = 90f, useCenter = true, topLeft = topLeft, size = arcSize)
    drawArc(color = tertiary, startAngle = 0f, sweepAngle = 90f, useCenter = true, topLeft = topLeft, size = arcSize)
    drawCircle(color = outline, radius = radius - 0.5.dp.toPx(), style = Stroke(width = 1.dp.toPx()))
  }
}

@Composable
private fun paletteName(id: String): String {
  return when (id) {
    UserPalettes.COLLIE_ID -> stringResource(R.string.preferences_appearance__color_collie)
    UserPalettes.SYSTEM_ID -> stringResource(R.string.preferences_appearance__color_system)
    else -> UserPalettes.byId(id)?.let { stringResource(it.nameRes) } ?: stringResource(R.string.preferences_appearance__color_collie)
  }
}
