package com.scamguard.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scamguard.Screen

@Composable
fun AppDock(selected: Screen, onSelect: (Screen) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.navigationBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(32.dp), color = Color(0xF5FFFFFF),
        shadowElevation = 12.dp, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White),
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(Screen.HOME to "Home", Screen.LEARN to "Learn", Screen.PROFILE to "Profile").forEach { (target, title) ->
                val isSelected = selected == target
                val fill by animateColorAsState(if (isSelected) Color(0xFFD7F0E9) else Color.Transparent, label = "tab")
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(26.dp)).background(fill)
                        .selectable(isSelected, role = Role.Tab, onClick = { onSelect(target) }).padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Canvas(Modifier.size(22.dp)) {
                        val c = if (isSelected) Color(0xFF15577A) else Color(0xFF5F6B7A)
                        val w = size.width; val h = size.height; val s = Stroke(1.7.dp.toPx())
                        when (target) {
                            Screen.HOME -> {
                                val p = Path().apply { moveTo(w*.08f,h*.43f); lineTo(w*.5f,h*.08f); lineTo(w*.92f,h*.43f); moveTo(w*.2f,h*.37f); lineTo(w*.2f,h*.9f); lineTo(w*.4f,h*.9f); lineTo(w*.4f,h*.62f); lineTo(w*.6f,h*.62f); lineTo(w*.6f,h*.9f); lineTo(w*.8f,h*.9f); lineTo(w*.8f,h*.37f) }
                                drawPath(p,c,style=s)
                            }
                            Screen.LEARN -> {
                                val p = Path().apply { moveTo(w*.5f,h*.2f); quadraticBezierTo(w*.3f,h*.04f,w*.08f,h*.16f); lineTo(w*.08f,h*.83f); quadraticBezierTo(w*.3f,h*.71f,w*.5f,h*.9f); quadraticBezierTo(w*.7f,h*.71f,w*.92f,h*.83f); lineTo(w*.92f,h*.16f); quadraticBezierTo(w*.7f,h*.04f,w*.5f,h*.2f); lineTo(w*.5f,h*.9f) }
                                drawPath(p,c,style=s)
                            }
                            else -> { drawCircle(c,w*.17f,Offset(w*.5f,h*.25f),style=s); drawArc(c,180f,180f,false,Offset(w*.15f,h*.5f),Size(w*.7f,h*.7f),style=s) }
                        }
                    }
                    Text(title,fontSize=11.sp,fontWeight=if(isSelected) FontWeight.SemiBold else FontWeight.Normal,color=if(isSelected) Color(0xFF15577A) else Neutral)
                }
            }
        }
    }
}
