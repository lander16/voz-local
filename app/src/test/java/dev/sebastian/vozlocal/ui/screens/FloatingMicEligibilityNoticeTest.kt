package dev.sebastian.vozlocal.ui.screens

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.sebastian.vozlocal.R
import dev.sebastian.vozlocal.ui.theme.MyApplicationTheme
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FloatingMicEligibilityNoticeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun eligibilityNoticeExplainsMandatoryOnScreenKeyboardWithoutABypassControl() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        composeTestRule.setContent {
            MyApplicationTheme(themeMode = "light") {
                FloatingMicEligibilityNotice()
            }
        }

        composeTestRule.onNodeWithTag("floating_mic_eligibility_notice").assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.floating_mic_eligibility_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.floating_mic_eligibility_description))
            .assertIsDisplayed()
        composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(0)
    }

    @Test
    fun eligibilityNoticeIsLocalizedInSpanish() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val spanishContext = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("es")) }
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides spanishContext) {
                MyApplicationTheme(themeMode = "light") {
                    FloatingMicEligibilityNotice()
                }
            }
        }

        composeTestRule.onNodeWithText(spanishContext.getString(R.string.floating_mic_eligibility_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(spanishContext.getString(R.string.floating_mic_eligibility_description))
            .assertIsDisplayed()
    }
}
