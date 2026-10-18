package ch.castellon.grok.usage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.castellon.grok.usage.R

@Composable
fun SignInScreen(
    onSignIn: () -> Unit,
    onOpenOfficial: () -> Unit,
    onPrivacy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Spacer(Modifier.height(28.dp))
            Text(
                text = stringResource(R.string.kicker),
                color = Muted,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.hero_title),
                color = Ink,
                fontSize = 34.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 40.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.hero_body),
                color = Muted,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            )
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onSignIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(stringResource(R.string.sign_in), fontSize = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onOpenOfficial,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
            ) {
                Text(stringResource(R.string.open_official), fontSize = 16.sp)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.not_affiliated),
                color = Muted,
                fontSize = 12.sp,
            )
            TextButton(onClick = onPrivacy) {
                Text(stringResource(R.string.privacy))
            }
        }
    }
}
