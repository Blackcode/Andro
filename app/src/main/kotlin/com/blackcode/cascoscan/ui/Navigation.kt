package com.blackcode.cascoscan.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.blackcode.cascoscan.CascoScanApp

object Routes {
    const val PROJECTS = "projects"
    const val SHEETS = "project/{projectId}"
    const val DRAWING = "sheet/{projectId}/{sheetId}"
    const val RECONCILE = "reconcile/{projectId}"
    const val REPORT = "report/{projectId}"

    fun sheets(projectId: String) = "project/$projectId"
    fun drawing(projectId: String, sheetId: String) = "sheet/$projectId/$sheetId"
    fun reconcile(projectId: String) = "reconcile/$projectId"
    fun report(projectId: String) = "report/$projectId"
}

@Composable
fun CascoScanNavHost(
    container: CascoScanApp.Container,
    incomingDocument: Uri? = null,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.PROJECTS) {
        composable(Routes.PROJECTS) {
            ProjectsScreen(
                container = container,
                incomingDocument = incomingDocument,
                onOpenProject = { projectId -> navController.navigate(Routes.sheets(projectId)) },
            )
        }
        composable(Routes.SHEETS) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            SheetsScreen(
                container = container,
                projectId = projectId,
                onBack = { navController.popBackStack() },
                onOpenSheet = { sheetId -> navController.navigate(Routes.drawing(projectId, sheetId)) },
                onReconcile = { navController.navigate(Routes.reconcile(projectId)) },
                onReport = { navController.navigate(Routes.report(projectId)) },
            )
        }
        composable(Routes.DRAWING) { entry ->
            DrawingScreen(
                container = container,
                projectId = entry.arguments?.getString("projectId").orEmpty(),
                sheetId = entry.arguments?.getString("sheetId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.RECONCILE) { entry ->
            ReconcileScreen(
                container = container,
                projectId = entry.arguments?.getString("projectId").orEmpty(),
                onBack = { navController.popBackStack() },
                onOpenSheet = { projectId, sheetId ->
                    navController.navigate(Routes.drawing(projectId, sheetId))
                },
            )
        }
        composable(Routes.REPORT) { entry ->
            ReportScreen(
                container = container,
                projectId = entry.arguments?.getString("projectId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/**
 * Builds a view model with a constructor argument, without pulling in a DI framework.
 *
 * The screens need real view models rather than `remember`: detection on a large sheet outlives a
 * rotation, and losing a half-finished run because the phone turned would be unforgivable on site.
 */
@Composable
inline fun <reified VM : ViewModel> screenViewModel(key: String? = null, crossinline build: () -> VM): VM =
    viewModel(
        key = key,
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return build() as T
            }
        },
    )
