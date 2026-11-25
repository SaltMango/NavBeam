package io.github.saltmango.navbeam.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.saltmango.navbeam.R
import io.github.saltmango.navbeam.ui.theme.*

@Composable
fun LoginScreen(
    onContinue: () -> Unit,
) {
    val colors = NavBeamTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colorStops = arrayOf(0f to Color(0xFF15191B), 0.6f to colors.bg1),
                    center = Offset(0.5f, 0f),
                    radius = Float.MAX_VALUE,
                )
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp),
        ) {
            // Brand & Hero
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = "NavBeam Brand Icon",
                    modifier = Modifier.size(140.dp),
                )

                Spacer(Modifier.height(30.dp))

                Text(
                    "NavBeam",
                    color = colors.textHi,
                    fontFamily = GeistMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp,
                    letterSpacing = 0.16.sp,
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    "Keep your screen off and your phone in your pocket.",
                    color = colors.textMid,
                    fontSize = 13.5.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Onboarding CTA
            Column(modifier = Modifier.padding(bottom = 40.dp)) {
                Text(
                    "Get Started",
                    color = Color.Black,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Primary)
                        .clickable { onContinue() }
                        .wrapContentHeight(Alignment.CenterVertically),
                )
            }
        }
    }
}
