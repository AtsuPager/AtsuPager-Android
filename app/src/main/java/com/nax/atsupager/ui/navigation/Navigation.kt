package com.nax.atsupager.ui.navigation

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Main : Screen("main/{userId}?call={call}") {
        fun createRoute(userId: String, call: String? = null) = 
            if (call != null) "main/$userId?call=$call" else "main/$userId"
    }
    object GroupChat : Screen("group_chat/{groupId}") {
        fun createRoute(groupId: String) = "group_chat/$groupId"
    }
    object Call : Screen("call")
    object Contacts : Screen("contacts?tab={tab}") {
        fun createRoute(tab: Int? = null) = 
            if (tab != null) "contacts?tab=$tab" else "contacts"
    }
    object Settings : Screen("settings")
    object Lock : Screen("lock")
    object Games : Screen("games/{userId}") {
        fun createRoute(userId: String) = "games/$userId"
    }
}
