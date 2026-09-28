package org.multipaz.samples.validatopia.shared.branding

/** A type-scale entry, in sp on Android and pt on iOS. */
data class TypeScaleEntry(val sizeSp: Float, val lineHeightSp: Float)

/** Validatopia's type scale, following the Material 3 type scale used across both apps. */
object ValidatopiaTypography {
    val displayLarge = TypeScaleEntry(sizeSp = 57f, lineHeightSp = 64f)
    val headlineMedium = TypeScaleEntry(sizeSp = 28f, lineHeightSp = 36f)
    val titleLarge = TypeScaleEntry(sizeSp = 22f, lineHeightSp = 28f)
    val bodyLarge = TypeScaleEntry(sizeSp = 16f, lineHeightSp = 24f)
    val bodyMedium = TypeScaleEntry(sizeSp = 14f, lineHeightSp = 20f)
    val labelLarge = TypeScaleEntry(sizeSp = 14f, lineHeightSp = 20f)
}
