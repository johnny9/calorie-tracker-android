package com.johnny9.calorietracker.domain

private val whitespace = Regex("\\s+")
private val titleSeparators = "-–—:|,•"

/**
 * Keeps a product title distinct from its separately displayed brand.
 *
 * USDA branded-food descriptions often repeat the brand at the beginning or
 * end of the product description. Only an exact, delimited brand is removed;
 * partial words and titles that consist solely of the brand are preserved.
 */
fun cleanFoodName(rawName: String, rawBrand: String?): String {
    val name = rawName.trim().replace(whitespace, " ")
    val brand = rawBrand?.trim()?.replace(whitespace, " ").orEmpty()
    if (name.isEmpty() || brand.length < 2 || name.equals(brand, ignoreCase = true)) return name

    val escapedBrand = Regex.escape(brand)
    val prefix = Regex(
        "^$escapedBrand(?:\\s*[®™©]\\s*)?(?:\\s*[$titleSeparators]\\s*|\\s+)",
        RegexOption.IGNORE_CASE,
    )
    val suffix = Regex(
        "(?:\\s*[$titleSeparators]\\s*|\\s+)$escapedBrand(?:\\s*[®™©])?$",
        RegexOption.IGNORE_CASE,
    )
    val cleaned = when {
        prefix.containsMatchIn(name) -> name.replaceFirst(prefix, "")
        suffix.containsMatchIn(name) -> name.replaceFirst(suffix, "")
        else -> name
    }.trim().trim(*titleSeparators.toCharArray()).trim()
    return cleaned.ifEmpty { name }
}
