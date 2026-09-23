// TEMPORARY D1/D2 HARNESS — D4 deletes this and makes HomeScreen the start destination.
//
// Only [DevMenuScreen] and the DEV_MENU route are temporary. [Routes.GAME] and [Routes.JAR] are
// permanent and D3/D4 build on them; the dev menu exists purely so D1 and D2 are reachable before
// there is a splash, a home screen or a level map.
package app.krafted.candytriangle.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.krafted.candytriangle.R
import app.krafted.candytriangle.level.LevelIds
import app.krafted.candytriangle.ui.game.GameScreen
import app.krafted.candytriangle.ui.jar.CandyJarScreen
import app.krafted.candytriangle.ui.theme.NightVoid

/** The app's route table. */
object Routes {

    /** TEMPORARY — D4 replaces this as the start destination with `HomeScreen`. */
    const val DEV_MENU = "dev_menu"

    /** PERMANENT. */
    const val GAME = "game/{levelId}"

    /** PERMANENT. */
    const val JAR = "jar"

    /** The level-id argument name, shared by [GAME] and its `navArgument`. */
    const val ARG_LEVEL_ID = "levelId"

    fun game(levelId: Int) = "game/$levelId"
}

/**
 * The single navigation graph, hosted by `MainActivity`.
 *
 * ## Why a real `NavHost` and not a `remember { mutableStateOf<Screen>() }` toggle
 *
 * `navigation-compose` gives every `NavBackStackEntry` its own `ViewModelStore`. Leaving a level
 * therefore calls `GameViewModel.onCleared()` — which is what stops the 60 Hz game thread and
 * releases the `LevelBoard`. A hand-rolled screen toggle keeps one Activity-scoped ViewModel alive
 * across level changes, leaking both the thread and the board, and §11's verification is JVM-only
 * so there is no emulator run that would catch it.
 *
 * ## Scope
 *
 * D1/D2 only. No splash, home, level map, results, settings, gem intro, world gate or crown UI —
 * those are D3 and D4. In particular there is deliberately **no route into a Sweet Room**: tier-3
 * jar unlocks are displayed by `CandyJarScreen` and entered from the map, which D3/D4 build.
 */
@Composable
fun CandyNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.DEV_MENU,
        modifier = modifier,
    ) {
        composable(Routes.DEV_MENU) {
            DevMenuScreen(
                onPlay = { levelId -> navController.navigate(Routes.game(levelId)) },
                onOpenJar = { navController.navigate(Routes.JAR) },
            )
        }

        composable(
            route = Routes.GAME,
            arguments = listOf(navArgument(Routes.ARG_LEVEL_ID) { type = NavType.IntType }),
        ) { backStackEntry ->
            val levelId = backStackEntry.arguments?.getInt(Routes.ARG_LEVEL_ID)
                ?: LevelIds.MAIN_FIRST
            GameScreen(
                levelId = levelId,
                onExit = { navController.popBackStack() },
                // D1/D2 have no results screen, so a finished level simply returns to the menu.
                // GameViewModel has already written crowns, the high score and the banked candies
                // by this point (plan deviation D1-b) — D4's LevelCompleteScreen must display that
                // write, not repeat it.
                onLevelFinished = { _, _, _, _ -> navController.popBackStack() },
            )
        }

        composable(Routes.JAR) {
            CandyJarScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * TEMPORARY D1/D2 HARNESS — D4 deletes this and makes HomeScreen the start destination.
 *
 * Deliberately unstyled: a level-id field, a Play button and a Candy Jar button, and nothing else.
 * Any polish spent here is polish thrown away in D4, and a styled dev menu invites someone to keep
 * it. It lives in this file so D4 deletes exactly one thing.
 */
@Composable
private fun DevMenuScreen(
    onPlay: (Int) -> Unit,
    onOpenJar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var levelText by rememberSaveable { mutableStateOf(LevelIds.MAIN_FIRST.toString()) }
    val levelId = remember(levelText) { levelText.toIntOrNull() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NightVoid)
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "D1/D2 dev menu — replaced by HomeScreen in D4.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = levelText,
            onValueChange = { levelText = it.filter(Char::isDigit).take(3) },
            label = { Text(stringResource(R.string.label_level)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Button(
            onClick = { levelId?.let(onPlay) },
            enabled = levelId != null,
        ) {
            Text(stringResource(R.string.action_play))
        }

        OutlinedButton(onClick = onOpenJar) {
            Text(stringResource(R.string.label_candy_jar))
        }
    }
}
