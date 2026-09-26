package com.rimuru.twobytwo

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rimuru.twobytwo.presentation.ComparisonViewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComparisonViewerTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun resultAndOriginalLayersExposeLocalizedDescriptions() {
        showViewer(resultAvailable = true)

        composeRule.onNodeWithContentDescription(string(R.string.compare_original)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.compare_enhanced)).assertExists()
    }

    @Test
    fun dividerExposesAdjustableSemantics() {
        showViewer(resultAvailable = true)

        composeRule.onNodeWithContentDescription(string(R.string.compare_handle))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.5f, 0f..1f),
                ),
            )
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "50%"))
    }

    @Test
    fun accessibilityProgressClampsToNormalizedBounds() {
        showViewer(resultAvailable = true)
        val divider = composeRule.onNodeWithContentDescription(string(R.string.compare_handle))

        divider.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            setProgress(1.25f)
        }
        composeRule.waitForIdle()
        divider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "100%"))

        divider.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            setProgress(-0.25f)
        }
        composeRule.waitForIdle()
        divider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0%"))
    }

    @Test
    fun resetRestoresMidpoint() {
        showViewer(resultAvailable = true)
        val divider = composeRule.onNodeWithContentDescription(string(R.string.compare_handle))
        divider.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            setProgress(1f)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(string(R.string.compare_reset)).performClick()
        composeRule.waitForIdle()

        divider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "50%"))
    }

    @Test
    fun previewOnlyLayerUsesPreviewDescription() {
        showViewer(resultAvailable = false)

        composeRule.onNodeWithContentDescription(string(R.string.compare_original)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.compare_preview)).assertExists()
        composeRule.onNodeWithContentDescription(string(R.string.compare_enhanced)).assertDoesNotExist()
    }

    private fun showViewer(resultAvailable: Boolean) {
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val localImageUri = "android.resource://$packageName/mipmap/ic_launcher"
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                Box(Modifier.size(320.dp, 240.dp)) {
                    ComparisonViewer(
                        originalUri = localImageUri,
                        resultUri = localImageUri.takeIf { resultAvailable },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun string(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
