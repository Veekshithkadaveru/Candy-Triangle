package app.krafted.candytriangle.ui.nav

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.krafted.candytriangle.level.CandyColor
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.ui.game.GameScreen
import app.krafted.candytriangle.ui.game.LevelOutcome
import app.krafted.candytriangle.ui.home.HomeScreen
import app.krafted.candytriangle.ui.home.SplashScreen
import app.krafted.candytriangle.ui.jar.CandyJarScreen
import app.krafted.candytriangle.ui.map.LevelMapScreen
import app.krafted.candytriangle.ui.results.LevelResultScreen
import app.krafted.candytriangle.ui.settings.SettingsScreen

/** The app's route table and the lossless result-path codec. */
object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val MAP = "map"
    const val GAME = "game/{levelId}"
    const val JAR = "jar"
    const val SETTINGS = "settings"

    const val ARG_LEVEL_ID = "levelId"
    private const val ARG_WON = "won"
    private const val ARG_CROWNS = "crowns"
    private const val ARG_SCORE = "score"
    private const val ARG_BALLS_REMAINING = "ballsRemaining"
    private const val ARG_SUGAR_RUSH = "sugarRushBonus"
    private const val ARG_GREEN = "green"
    private const val ARG_PURPLE = "purple"
    private const val ARG_PINK = "pink"
    private const val ARG_BLUE = "blue"

    const val RESULT =
        "result/{$ARG_LEVEL_ID}/{$ARG_WON}/{$ARG_CROWNS}/{$ARG_SCORE}/" +
            "{$ARG_BALLS_REMAINING}/{$ARG_SUGAR_RUSH}/{$ARG_GREEN}/{$ARG_PURPLE}/{$ARG_PINK}/{$ARG_BLUE}"

    val resultArguments = listOf(
        navArgument(ARG_LEVEL_ID) { type = NavType.IntType },
        navArgument(ARG_WON) { type = NavType.BoolType },
        navArgument(ARG_CROWNS) { type = NavType.IntType },
        navArgument(ARG_SCORE) { type = NavType.IntType },
        navArgument(ARG_BALLS_REMAINING) { type = NavType.IntType },
        navArgument(ARG_SUGAR_RUSH) { type = NavType.IntType },
        navArgument(ARG_GREEN) { type = NavType.IntType },
        navArgument(ARG_PURPLE) { type = NavType.IntType },
        navArgument(ARG_PINK) { type = NavType.IntType },
        navArgument(ARG_BLUE) { type = NavType.IntType },
    )

    fun game(levelId: Int): String = "game/$levelId"

    fun result(outcome: LevelOutcome): String = buildString {
        append("result/")
        append(outcome.levelId).append('/')
        append(outcome.won).append('/')
        append(outcome.crowns).append('/')
        append(outcome.score).append('/')
        append(outcome.ballsRemaining).append('/')
        append(outcome.sugarRushBonus).append('/')
        append(outcome.collected[CandyColor.GREEN] ?: 0).append('/')
        append(outcome.collected[CandyColor.PURPLE] ?: 0).append('/')
        append(outcome.collected[CandyColor.PINK] ?: 0).append('/')
        append(outcome.collected[CandyColor.BLUE] ?: 0)
    }

    fun outcome(entry: NavBackStackEntry): LevelOutcome {
        val args = requireNotNull(entry.arguments)
        val collected = buildMap {
            fun add(color: CandyColor, key: String) {
                val count = args.getInt(key)
                if (count > 0) put(color, count)
            }
            add(CandyColor.GREEN, ARG_GREEN)
            add(CandyColor.PURPLE, ARG_PURPLE)
            add(CandyColor.PINK, ARG_PINK)
            add(CandyColor.BLUE, ARG_BLUE)
        }
        return LevelOutcome(
            levelId = args.getInt(ARG_LEVEL_ID),
            won = args.getBoolean(ARG_WON),
            crowns = args.getInt(ARG_CROWNS),
            score = args.getInt(ARG_SCORE),
            ballsRemaining = args.getInt(ARG_BALLS_REMAINING),
            collected = collected,
            sugarRushBonus = args.getInt(ARG_SUGAR_RUSH),
        )
    }
}

/**
 * The single destination graph. Each level owns its own back-stack `ViewModelStore`, so removing
 * the game destination before showing results also tears down the board and game thread.
 */
@Composable
fun CandyNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        modifier = modifier,
        enterTransition = {
            fadeIn(tween(durationMillis = 220, delayMillis = 70)) +
                slideInHorizontally(
                    animationSpec = tween(360, easing = FastOutSlowInEasing),
                    initialOffsetX = { it / 10 },
                )
        },
        exitTransition = {
            fadeOut(tween(180)) +
                slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 14 }
        },
        popEnterTransition = {
            fadeIn(tween(durationMillis = 220, delayMillis = 50)) +
                slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { -it / 10 }
        },
        popExitTransition = {
            fadeOut(tween(180)) +
                slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 14 }
        },
    ) {
        composable(Routes.SPLASH) { entry ->
            SplashScreen(
                onFinished = {
                    entry.ifResumed {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.SPLASH) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                },
            )
        }

        composable(Routes.HOME) { entry ->
            HomeScreen(
                onPlay = { entry.ifResumed { navController.navigate(Routes.MAP) } },
                onOpenJar = { entry.ifResumed { navController.navigate(Routes.JAR) } },
                onOpenSettings = { entry.ifResumed { navController.navigate(Routes.SETTINGS) } },
            )
        }

        composable(Routes.MAP) { entry ->
            LevelMapScreen(
                onBack = { entry.ifResumed { navController.popBackStack() } },
                onPlayLevel = { levelId ->
                    entry.ifResumed { navController.navigate(Routes.game(levelId)) }
                },
                onOpenJar = { entry.ifResumed { navController.navigate(Routes.JAR) } },
            )
        }

        composable(
            route = Routes.GAME,
            arguments = listOf(navArgument(Routes.ARG_LEVEL_ID) { type = NavType.IntType }),
        ) { entry ->
            val levelId = entry.arguments?.getInt(Routes.ARG_LEVEL_ID) ?: LevelIds.MAIN_FIRST
            GameScreen(
                levelId = levelId,
                onExit = { entry.ifResumed { navController.popBackStack() } },
                onLevelFinished = { outcome ->
                    entry.ifResumed {
                        navController.navigate(Routes.result(outcome)) {
                            popUpTo(Routes.GAME) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(route = Routes.RESULT, arguments = Routes.resultArguments) { entry ->
            val outcome = Routes.outcome(entry)
            LevelResultScreen(
                outcome = outcome,
                onContinue = { entry.ifResumed { navController.popBackStack() } },
                onRetry = {
                    entry.ifResumed {
                        navController.navigate(Routes.game(outcome.levelId)) {
                            popUpTo(Routes.RESULT) { inclusive = true }
                        }
                    }
                },
                onHome = {
                    entry.ifResumed {
                        if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                            navController.navigate(Routes.HOME) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        }
                    }
                },
            )
        }

        composable(Routes.JAR) { entry ->
            CandyJarScreen(onBack = { entry.ifResumed { navController.popBackStack() } })
        }

        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(onBack = { entry.ifResumed { navController.popBackStack() } })
        }
    }
}

/** Drops double taps while a destination transition is already underway. */
private inline fun NavBackStackEntry.ifResumed(block: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) block()
}
