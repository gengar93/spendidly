package io.github.gengar93.tally.ui

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.graphics.shapes.RoundedPolygon

/** A category's look: a Material expressive shape, a tonal role from the colour scheme, and a glyph. */
data class CategoryStyle(val shape: RoundedPolygon, val role: Role, val icon: ImageVector)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun categoryStyle(name: String?): CategoryStyle {
    val n = name?.lowercase().orEmpty()
    fun has(vararg words: String) = words.any { it in n }
    val s = MaterialShapes
    return when {
        name == null || n == "uncategorized" -> CategoryStyle(s.Circle, Role.Secondary, Glyph.Uncategorized)
        has("food", "snack", "eat", "meal", "restaurant") -> CategoryStyle(s.Cookie7Sided, Role.Tertiary, Glyph.Food)
        has("grocer") -> CategoryStyle(s.Clover4Leaf, Role.Secondary, Glyph.Groceries)
        has("transport", "travel", "cab", "commute") -> CategoryStyle(s.Pill, Role.Primary, Glyph.Transport)
        has("bill", "utilit", "subscription") -> CategoryStyle(s.Arch, Role.Secondary, Glyph.Bills)
        has("home", "rent", "house") -> CategoryStyle(s.Gem, Role.Tertiary, Glyph.Home)
        has("personal", "health", "fitness", "groom") -> CategoryStyle(s.Flower, Role.Primary, Glyph.Personal)
        has("family", "gift") -> CategoryStyle(s.Sunny, Role.Tertiary, Glyph.Family)
        has("accessor") -> CategoryStyle(s.Cookie12Sided, Role.Primary, Glyph.Accessories)
        has("electronic", "gadget", "device") -> CategoryStyle(s.Pentagon, Role.Primary, Glyph.Electronics)
        has("cloth", "fashion", "apparel") -> CategoryStyle(s.Puffy, Role.Tertiary, Glyph.Clothes)
        has("digital", "app", "software") -> CategoryStyle(s.Cookie6Sided, Role.Secondary, Glyph.Digital)
        has("learn", "course", "book", "educat") -> CategoryStyle(s.Bun, Role.Secondary, Glyph.Learning)
        has("other", "misc") -> CategoryStyle(s.Circle, Role.Secondary, Glyph.Other)
        else -> listOf(s.Cookie9Sided, s.SoftBurst, s.Clover8Leaf, s.PuffyDiamond).let { shapes ->
            val i = Math.floorMod(n.hashCode(), shapes.size)
            CategoryStyle(shapes[i], Role.entries[i % 3], Glyph.Other)
        }
    }
}
