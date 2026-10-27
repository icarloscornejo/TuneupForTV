package com.prototype.tuneupfortv

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prototype.tuneupfortv.store.AppSettings
import com.prototype.tuneupfortv.store.SettingsStore
import com.prototype.tuneupfortv.ui.AppLanguage
import com.prototype.tuneupfortv.ui.LocalAppIcons
import com.prototype.tuneupfortv.ui.PackageManagerIconSource
import com.prototype.tuneupfortv.ui.TuneupApp
import com.prototype.tuneupfortv.ui.TuneupTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        viewModelFactory {
            initializer {
                val container = AppContainer.of(application)
                AppViewModel(
                    container.settingsStorage,
                    container.newSession(),
                    loadDebloatLists = container::loadDebloatLists,
                    fetchLauncherRelease = container::fetchLauncherRelease,
                    downloadLauncher = container::downloadLauncher,
                )
            }
        }
    }

    /** The language this activity was started with: when the saved one differs, the activity restarts. */
    private var appliedLanguageTag: String = AppSettings().language

    override fun attachBaseContext(newBase: Context) {
        appliedLanguageTag = SettingsStore(newBase.filesDir).load().language
        super.attachBaseContext(AppLanguage.fromTag(appliedLanguageTag).wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.uiState.collectAsState()
            TuneupTheme {
                // Names and icons come from this device's package manager, read lazily as tiles appear.
                val iconSource = remember { PackageManagerIconSource(applicationContext) }
                CompositionLocalProvider(LocalAppIcons provides iconSource) {
                    TuneupApp(state = state, actions = viewModel)
                    // The language was saved: restart so every string is read again in it.
                    LaunchedEffect(state.settings.language) {
                        if (state.settings.language != appliedLanguageTag) recreate()
                    }
                }
            }
        }
    }
}
