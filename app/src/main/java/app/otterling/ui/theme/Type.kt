package app.otterling.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.otterling.R

/**
 * Plus Jakarta Sans is bundled as a single variable font (res/font/plus_jakarta_sans_variable.ttf)
 * to match the web dashboard's `--font-sans` (see filter-server/dashboard/src/styles/theme.css).
 * Each weight is declared as its own [Font] entry pinning the `wght` axis via [FontVariation]
 * (supported on API 26+, and this app's minSdk is 28), so the M3 type scale below can request
 * 400/500/600/700 from the one file. If the font resource is ever missing, swap
 * [PlusJakartaSansFontFamily] for [FontFamily.SansSerif] — nothing else needs to change.
 */
@OptIn(ExperimentalTextApi::class)
private fun plusJakartaSansFont(weight: Int) = Font(
    R.font.plus_jakarta_sans_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val PlusJakartaSansFontFamily = FontFamily(
    plusJakartaSansFont(400),
    plusJakartaSansFont(500),
    plusJakartaSansFont(600),
    plusJakartaSansFont(700),
)

private val default = Typography()

val FamilyGuardTypography = Typography(
    displayLarge = default.displayLarge.copy(fontFamily = PlusJakartaSansFontFamily, fontWeight = FontWeight.Bold),
    displayMedium = default.displayMedium.copy(fontFamily = PlusJakartaSansFontFamily, fontWeight = FontWeight.Bold),
    displaySmall = default.displaySmall.copy(fontFamily = PlusJakartaSansFontFamily, fontWeight = FontWeight.Bold),
    headlineLarge = default.headlineLarge.copy(fontFamily = PlusJakartaSansFontFamily, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = default.bodyLarge.copy(fontFamily = PlusJakartaSansFontFamily),
    bodyMedium = default.bodyMedium.copy(fontFamily = PlusJakartaSansFontFamily),
    bodySmall = default.bodySmall.copy(fontFamily = PlusJakartaSansFontFamily),
    labelLarge = default.labelLarge.copy(fontFamily = PlusJakartaSansFontFamily, fontWeight = FontWeight.Medium),
    labelMedium = default.labelMedium.copy(fontFamily = PlusJakartaSansFontFamily, fontWeight = FontWeight.Medium),
    labelSmall = default.labelSmall.copy(fontFamily = PlusJakartaSansFontFamily, fontWeight = FontWeight.Medium),
)
