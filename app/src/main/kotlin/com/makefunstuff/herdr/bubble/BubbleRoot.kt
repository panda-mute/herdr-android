package com.makefunstuff.herdr.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.makefunstuff.herdr.AppContainer
import com.makefunstuff.herdr.screens
import com.makefunstuff.herdr.ui.agents.AgentScreen
import com.makefunstuff.herdr.ui.agents.TasksScreen
import com.makefunstuff.herdr.ui.common.LocalContainer
import com.makefunstuff.herdr.ui.coordinator.CoordinatorScreen
import com.makefunstuff.herdr.ui.design.ToastHost
import com.makefunstuff.herdr.ui.theme.Herdr
import com.makefunstuff.herdr.ui.theme.HerdrTheme

private const val CHAT = "bubble"

/**
 * A floating chat: [chat]'s screen as the bubble's window, with the app's other screens behind it for
 * what the chat opens (its terminal). [nav] and [dark] are given by the desktop preview's scenes.
 */
@Composable
fun BubbleRoot(container: AppContainer, chat: BubbleChat, dark: Boolean = isSystemInDarkTheme(), nav: NavHostController = rememberNavController()) {
    CompositionLocalProvider(LocalContainer provides container) {
        HerdrTheme(dark) {
            Box(Modifier.fillMaxSize().background(Herdr.colors.ground)) { com.makefunstuff.herdr.ui.common.GuardHost(container.guard) {
                val tasksList = rememberLazyListState()
                NavHost(nav, startDestination = CHAT, modifier = Modifier.fillMaxSize()) {
                    // The chat the bubble opened on: it has no screen to go back to, and opens in the app instead of floating.
                    composable(CHAT) {
                        CompositionLocalProvider(LocalBubble provides chat) {
                            when (chat) {
                                is BubbleChat.Agent -> AgentScreen(nav, chat.machineId, chat.paneId)
                                is BubbleChat.Coordinator -> CoordinatorScreen(nav, chat.machineId)
                            }
                        }
                    }
                    screens(nav) { TasksScreen(nav, tasksList) }
                }
                }
                ToastHost()
            }
        }
    }
}
