package it.spesami.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import it.spesami.core.AppData
import it.spesami.core.DefaultCategories
import it.spesami.core.ShoppingItem
import it.spesami.core.ShoppingProgress
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** The checklist row and progress card in the Light and Dracula themes. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34])
class ShoppingThemeTest(private val dark: Boolean) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes() = listOf(arrayOf<Any>(false), arrayOf<Any>(true))
    }

    @get:Rule
    val compose = createComposeRule()

    private val data = AppData(categories = DefaultCategories.seed())

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test
    fun rowTogglesAndProgressRenders() {
        var scheme: ColorScheme? = null
        var toggles = 0
        compose.setContent {
            SpesamiTheme(dark) {
                scheme = MaterialTheme.colorScheme
                Surface {
                    var purchased by remember { mutableStateOf(false) }
                    Column {
                        ShoppingProgressCard(ShoppingProgress(8, 15), mealCount = 6)
                        ShoppingRow(
                            item = ShoppingItem(1, "Patate", 1, count = 4, purchased = purchased),
                            data = data,
                            onToggle = { purchased = it; toggles++ },
                            onCategory = {},
                            onRemove = {},
                        )
                        ShoppingRow(
                            item = ShoppingItem(2, "Detersivo", 12, count = 0, manual = true),
                            data = data,
                            onToggle = {},
                            onCategory = {},
                            onRemove = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("8 di 15 prodotti acquistati").assertIsDisplayed()
        compose.onNodeWithText("53%").assertIsDisplayed()
        compose.onNodeWithText("×4").assertIsDisplayed()
        compose.onNodeWithText("Aggiunto a mano").assertIsDisplayed()
        compose.onNodeWithContentDescription("Opzioni per Patate").assertIsDisplayed()

        // The whole row is the checkbox: one tap, no confirmation, and back.
        val row = compose.onNodeWithText("Patate", useUnmergedTree = false)
        row.assertIsOff()
        row.performClick()
        row.assertIsOn()
        row.performClick()
        row.assertIsOff()
        assertTrue(toggles == 2)

        // Purchased (dimmed) and pending text both stay readable on this theme's background.
        val s = scheme!!
        assertTrue(dark == (s.surface.luminance() < 0.5f))
        assertTrue("pending text contrast", contrast(s.onSurface, s.surface) >= 7f)
        assertTrue("purchased text contrast", contrast(s.onSurfaceVariant, s.surface) >= 4.5f)
        assertTrue("progress bar visible", contrast(s.primary, s.surfaceVariant) >= 1.5f)
    }
}
