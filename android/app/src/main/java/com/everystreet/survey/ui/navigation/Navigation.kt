package com.everystreet.survey.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.everystreet.survey.ui.screens.*

/**
 * Navigation routes
 */
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object AreaSelection : Screen("area_selection")
    object RouteCalculation : Screen("route_calculation/{areaName}/{north}/{south}/{east}/{west}") {
        fun createRoute(areaName: String, north: Double, south: Double, east: Double, west: Double): String {
            return "route_calculation/$areaName/$north/$south/$east/$west"
        }
    }
    object RouteList : Screen("route_list")
    object Navigation : Screen("navigation/{routeId}") {
        fun createRoute(routeId: Long): String {
            return "navigation/$routeId"
        }
    }
    object Settings : Screen("settings")
}

@Composable
fun EveryStreetNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToAreaSelection = {
                    navController.navigate(Screen.AreaSelection.route)
                },
                onNavigateToRouteList = {
                    navController.navigate(Screen.RouteList.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToRoute = { routeId ->
                    navController.navigate(Screen.Navigation.createRoute(routeId))
                }
            )
        }

        composable(Screen.AreaSelection.route) {
            AreaSelectionScreen(
                onAreaSelected = { bounds ->
                    navController.navigate(
                        Screen.RouteCalculation.createRoute(
                            bounds.name,
                            bounds.north,
                            bounds.south,
                            bounds.east,
                            bounds.west
                        )
                    )
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.RouteCalculation.route,
            arguments = listOf(
                navArgument("areaName") { type = NavType.StringType },
                navArgument("north") { type = NavType.StringType },
                navArgument("south") { type = NavType.StringType },
                navArgument("east") { type = NavType.StringType },
                navArgument("west") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val areaName = backStackEntry.arguments?.getString("areaName") ?: ""
            val north = backStackEntry.arguments?.getString("north")?.toDoubleOrNull() ?: 0.0
            val south = backStackEntry.arguments?.getString("south")?.toDoubleOrNull() ?: 0.0
            val east = backStackEntry.arguments?.getString("east")?.toDoubleOrNull() ?: 0.0
            val west = backStackEntry.arguments?.getString("west")?.toDoubleOrNull() ?: 0.0

            RouteCalculationScreen(
                areaName = areaName,
                north = north,
                south = south,
                east = east,
                west = west,
                onRouteCreated = { routeId ->
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                    navController.navigate(Screen.Navigation.createRoute(routeId))
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.RouteList.route) {
            RouteListScreen(
                onRouteSelected = { routeId ->
                    navController.navigate(Screen.Navigation.createRoute(routeId))
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.Navigation.route,
            arguments = listOf(
                navArgument("routeId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val routeId = backStackEntry.arguments?.getLong("routeId") ?: 0L
            NavigationScreen(
                routeId = routeId,
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
