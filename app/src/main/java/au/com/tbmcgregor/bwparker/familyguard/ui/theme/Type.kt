package au.com.tbmcgregor.bwparker.familyguard.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import au.com.tbmcgregor.bwparker.familyguard.R

/**
 * Inter is bundled as a single variable font (res/font/inter_variable.ttf). Each weight is
 * declared as its own [Font] entry pinning the `wght` axis via [FontVariation] (supported on
 * API 26+, and this app's minSdk is 28), so the M3 type scale below can request 400/500/600/700
 * from the one file. If the font resource is ever missing, swap [InterFontFamily] for
 * [FontFamily.SansSerif] — nothing else needs to change.
 */
@OptIn(ExperimentalTextApi::class)
private fun interFont(weight: Int) = Font(
    R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val InterFontFamily = FontFamily(
    interFont(400),
    interFont(500),
    interFont(600),
    interFont(700),
)

private val default = Typography()

val FamilyGuardTypography = Typography(
    displayLarge = default.displayLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold),
    displayMedium = default.displayMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold),
    displaySmall = default.displaySmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold),
    headlineLarge = default.headlineLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = default.bodyLarge.copy(fontFamily = InterFontFamily),
    bodyMedium = default.bodyMedium.copy(fontFamily = InterFontFamily),
    bodySmall = default.bodySmall.copy(fontFamily = InterFontFamily),
    labelLarge = default.labelLarge.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
    labelMedium = default.labelMedium.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
    labelSmall = default.labelSmall.copy(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium),
)
