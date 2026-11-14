package com.ansardeo.babnext

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun permanentTabsAndPositionsStructure() {
        listOf("Home", "Manual", "Positions", "Settings", "More").forEach {
            compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
        }
        compose.onNodeWithText("Positions", useUnmergedTree = true).performClick()
        listOf("Open Orders", "History", "Open", "Closed").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Open Orders").performClick()
        listOf("Basic", "Conditional").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("History").performClick()
        listOf("Open", "Closed").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }
}
