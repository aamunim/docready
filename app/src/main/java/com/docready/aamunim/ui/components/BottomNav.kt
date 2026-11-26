package com.docready.aamunim.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One tab of the bottom navigation */
class NavTab(val key: String, val route: String, val label: String, val icon: ImageVector)

/**
 * The bottom navigation (74 dp): each tab an icon over its name; the current one in green with
 * a soft pill (56 × 30) behind its icon. Tabs come as a list, so one can be added (Folders).
 */
@Composable
fun AppBottomBar(tabs: List<NavTab>, current: String?, onTab: (NavTab) -> Unit) {
  Surface(color = MaterialTheme.colorScheme.surface) {
    Column {
      HorizontalDivider(color = MaterialTheme.colorScheme.outline)
      Row(
        Modifier.fillMaxWidth().navigationBarsPadding().height(74.dp).padding(start = 6.dp, end = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        for (t in tabs) {
          val active = t.route == current
          val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
          Column(
            Modifier
              .weight(1f)
              .fillMaxHeight()
              .selectable(selected = active, role = Role.Tab, onClick = { onTab(t) }),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Box(
              Modifier
                .size(width = 56.dp, height = 30.dp)
                .background(if (active) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(15.dp)),
              contentAlignment = Alignment.Center,
            ) {
              Icon(t.icon, contentDescription = null, tint = color, modifier = Modifier.size(21.dp))
            }
            Text(
              t.label,
              color = color,
              fontSize = 11.5.sp,
              fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
              modifier = Modifier.padding(top = 4.dp),
              maxLines = 1,
            )
          }
        }
      }
    }
  }
}
