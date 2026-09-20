package com.personalsky.app

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personalsky.app.data.local.CheckInType
import com.personalsky.app.data.local.StoredGoal
import com.personalsky.app.astronomy.AstronomyGuide
import com.personalsky.app.astronomy.AstronomyTone
import com.personalsky.app.data.reminder.ReminderScheduler
import com.personalsky.app.ui.theme.PersonalSkyTheme
import com.personalsky.app.wallpaper.WallpaperExporter
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PersonalSkyTheme {
                PersonalSkyApp()
            }
        }
    }
}

private enum class AppScreen {
    WELCOME,
    HOW_IT_WORKS,
    CREATE_GOAL,
    PREVIEW_CONSTELLATION,
    ACTIVE_SKY,
    HISTORY,
    REMINDER_SETTINGS,
    EXPERIENCE_SETTINGS,
    COMPLETION,
    ARCHIVE,
    ARCHIVED_DETAIL,
    GOAL_SETTINGS,
    ASTRONOMY_GUIDE,
    SKY_OVERVIEW,
    SUPPORT_PROJECT
}

private enum class HistoryDayVisual {
    STANDARD,
    BRIGHT,
    NEBULA,
    TODAY,
    FUTURE
}

private data class GoalDraft(
    val title: String = "",
    val durationDays: Int = 7
)

@Composable
private fun PersonalSkyApp(
    goalViewModel: GoalViewModel = viewModel()
) {
    val storedGoal by goalViewModel.activeGoal.collectAsState()
    val completedGoal by goalViewModel.completedGoal.collectAsState()
    val archivedGoals by goalViewModel.archivedGoals.collectAsState()
    val reminderSettings by goalViewModel.reminderSettings.collectAsState()
    val skyStatistics by goalViewModel.skyStatistics.collectAsState()
    var currentScreen by remember { mutableStateOf(AppScreen.WELCOME) }
    var draft by remember { mutableStateOf(GoalDraft()) }
    var selectedArchivedGoal by remember { mutableStateOf<StoredGoal?>(null) }
    var guideDuration by remember { mutableIntStateOf(7) }
    var guideInitialToneIndex by remember { mutableIntStateOf(0) }
    var guideReturnScreen by remember { mutableStateOf(AppScreen.ACTIVE_SKY) }
    val context = LocalContext.current
    var showTestAfterPermission by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && showTestAfterPermission) {
            ReminderScheduler.showTestReminder(context)
        }
        showTestAfterPermission = false
    }

    fun requestNotificationPermissionIfNeeded(showTestAfterGrant: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            showTestAfterPermission = showTestAfterGrant
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun testNotification() {
        if (hasNotificationPermission(context)) {
            ReminderScheduler.showTestReminder(context)
        } else {
            requestNotificationPermissionIfNeeded(showTestAfterGrant = true)
        }
    }

    fun openAstronomyGuide(durationDays: Int, returnScreen: AppScreen) {
        guideDuration = durationDays
        guideInitialToneIndex = archivedGoals.count { it.durationDays == durationDays } % AstronomyTone.entries.size
        guideReturnScreen = returnScreen
        currentScreen = AppScreen.ASTRONOMY_GUIDE
    }

    fun openPrivacyPolicy() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    BackHandler(
        enabled = currentScreen != AppScreen.ACTIVE_SKY &&
                currentScreen != AppScreen.WELCOME
    ) {
        when (currentScreen) {
            AppScreen.HOW_IT_WORKS -> currentScreen = AppScreen.WELCOME
            AppScreen.CREATE_GOAL -> {
                currentScreen = if (storedGoal != null) AppScreen.ACTIVE_SKY else AppScreen.WELCOME
            }
            AppScreen.PREVIEW_CONSTELLATION -> currentScreen = AppScreen.CREATE_GOAL
            AppScreen.HISTORY -> currentScreen = AppScreen.ACTIVE_SKY
            AppScreen.REMINDER_SETTINGS -> currentScreen = AppScreen.ACTIVE_SKY
            AppScreen.EXPERIENCE_SETTINGS -> currentScreen = AppScreen.ACTIVE_SKY
            AppScreen.GOAL_SETTINGS -> currentScreen = AppScreen.ACTIVE_SKY
            AppScreen.SKY_OVERVIEW -> currentScreen = AppScreen.ACTIVE_SKY
            AppScreen.SUPPORT_PROJECT -> currentScreen = AppScreen.SKY_OVERVIEW
            AppScreen.ASTRONOMY_GUIDE -> currentScreen = guideReturnScreen
            AppScreen.ARCHIVE -> currentScreen = AppScreen.HISTORY
            AppScreen.ARCHIVED_DETAIL -> currentScreen = AppScreen.ARCHIVE
            AppScreen.COMPLETION -> Unit
            AppScreen.WELCOME,
            AppScreen.ACTIVE_SKY -> Unit
        }
    }

    LaunchedEffect(storedGoal, completedGoal) {
        when {
            completedGoal != null -> currentScreen = AppScreen.COMPLETION
            storedGoal != null && currentScreen == AppScreen.WELCOME -> {
                currentScreen = AppScreen.ACTIVE_SKY
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = AmoledBlack,
        contentColor = StarWhite
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Starfield(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.8f)
            )

            when (currentScreen) {
                AppScreen.WELCOME -> WelcomeScreen(
                    onCreateGoal = { currentScreen = AppScreen.CREATE_GOAL },
                    onHowItWorks = { currentScreen = AppScreen.HOW_IT_WORKS }
                )

                AppScreen.HOW_IT_WORKS -> HowItWorksScreen(
                    onBack = { currentScreen = AppScreen.WELCOME },
                    onStart = { currentScreen = AppScreen.CREATE_GOAL }
                )

                AppScreen.CREATE_GOAL -> CreateGoalScreen(
                    initialDraft = draft,
                    initialReminderEnabled = reminderSettings.enabled,
                    initialReminderHour = reminderSettings.hour,
                    initialReminderMinute = reminderSettings.minute,
                    onBack = { currentScreen = AppScreen.WELCOME },
                    onPreview = { title, duration, reminderEnabled, reminderHour, reminderMinute ->
                        draft = GoalDraft(title = title, durationDays = duration)
                        goalViewModel.saveReminderSettings(
                            enabled = reminderEnabled,
                            hour = reminderHour,
                            minute = reminderMinute
                        )
                        goalViewModel.createGoal(title, duration)
                        if (reminderEnabled) requestNotificationPermissionIfNeeded()
                        currentScreen = AppScreen.PREVIEW_CONSTELLATION
                    }
                )

                AppScreen.PREVIEW_CONSTELLATION -> ConstellationPreviewScreen(
                    draft = draft,
                    onBack = { currentScreen = AppScreen.CREATE_GOAL },
                    onStart = { currentScreen = AppScreen.ACTIVE_SKY }
                )

                AppScreen.ACTIVE_SKY -> ActiveSkyScreen(
                    storedGoal = storedGoal,
                    fallbackDraft = draft,
                    reminderEnabled = reminderSettings.enabled,
                    reminderHour = reminderSettings.hour,
                    reminderMinute = reminderSettings.minute,
                    hapticsEnabled = reminderSettings.hapticsEnabled,
                    soundEnabled = reminderSettings.soundEnabled,
                    reducedMotionEnabled = reminderSettings.reducedMotionEnabled,
                    onRecordStandard = goalViewModel::recordTodayStandard,
                    onRecordBright = goalViewModel::recordTodayBright,
                    onOpenHistory = { currentScreen = AppScreen.HISTORY },
                    onOpenExperienceSettings = { currentScreen = AppScreen.EXPERIENCE_SETTINGS },
                    onOpenGoalSettings = { currentScreen = AppScreen.GOAL_SETTINGS }
                )

                AppScreen.HISTORY -> {
                    val goal = storedGoal
                    if (goal != null) {
                        HistoryScreen(
                            goal = goal,
                            onBack = { currentScreen = AppScreen.ACTIVE_SKY },
                            onOpenSkyOverview = {
                                goalViewModel.refreshSkyStatistics()
                                currentScreen = AppScreen.SKY_OVERVIEW
                            },
                            onOpenArchive = {
                                goalViewModel.refreshArchivedGoals()
                                currentScreen = AppScreen.ARCHIVE
                            }
                        )
                    } else {
                        currentScreen = AppScreen.ACTIVE_SKY
                    }
                }

                AppScreen.ARCHIVE -> ArchiveScreen(
                    goals = archivedGoals,
                    onBack = { currentScreen = AppScreen.HISTORY },
                    onOpenGoal = { goal ->
                        selectedArchivedGoal = goal
                        currentScreen = AppScreen.ARCHIVED_DETAIL
                    }
                )

                AppScreen.ARCHIVED_DETAIL -> {
                    val goal = selectedArchivedGoal
                    if (goal != null) {
                        ArchivedDetailScreen(
                            goal = goal,
                            reducedMotionEnabled = reminderSettings.reducedMotionEnabled,
                            onOpenAstronomyGuide = { durationDays ->
                                openAstronomyGuide(durationDays, AppScreen.ARCHIVED_DETAIL)
                            },
                            onBack = { currentScreen = AppScreen.ARCHIVE }
                        )
                    } else {
                        currentScreen = AppScreen.ARCHIVE
                    }
                }

                AppScreen.COMPLETION -> {
                    val goal = completedGoal
                    if (goal != null) {
                        CompletionScreen(
                            goal = goal,
                            reducedMotionEnabled = reminderSettings.reducedMotionEnabled,
                            onOpenAstronomyGuide = {
                                openAstronomyGuide(goal.durationDays, AppScreen.COMPLETION)
                            },
                            onStartNewGoal = {
                                goalViewModel.archiveCompletedGoal()
                                draft = GoalDraft()
                                currentScreen = AppScreen.CREATE_GOAL
                            }
                        )
                    } else {
                        currentScreen = AppScreen.WELCOME
                    }
                }

                AppScreen.ASTRONOMY_GUIDE -> AstronomyGuideScreen(
                    durationDays = guideDuration,
                    initialToneIndex = guideInitialToneIndex,
                    onBack = { currentScreen = guideReturnScreen }
                )

                AppScreen.SKY_OVERVIEW -> SkyOverviewScreen(
                    statistics = skyStatistics,
                    onOpenArchive = {
                        goalViewModel.refreshArchivedGoals()
                        currentScreen = AppScreen.ARCHIVE
                    },
                    onOpenSupport = { currentScreen = AppScreen.SUPPORT_PROJECT },
                    onBack = { currentScreen = AppScreen.ACTIVE_SKY }
                )

                AppScreen.SUPPORT_PROJECT -> SupportProjectScreen(
                    onBack = { currentScreen = AppScreen.SKY_OVERVIEW }
                )

                AppScreen.GOAL_SETTINGS -> {
                    val goal = storedGoal
                    if (goal != null) {
                        GoalManagementScreen(
                            goal = goal,
                            onSaveTitle = { title ->
                                goalViewModel.renameActiveGoal(title)
                                currentScreen = AppScreen.ACTIVE_SKY
                            },
                            onOpenExperienceSettings = {
                                currentScreen = AppScreen.EXPERIENCE_SETTINGS
                            },
                            onFinishEarly = goalViewModel::finishActiveGoalEarly,
                            onClearAllData = {
                                goalViewModel.clearAllData()
                                draft = GoalDraft()
                                selectedArchivedGoal = null
                                currentScreen = AppScreen.WELCOME
                            },
                            onBack = { currentScreen = AppScreen.ACTIVE_SKY }
                        )
                    } else {
                        currentScreen = AppScreen.ACTIVE_SKY
                    }
                }

                AppScreen.EXPERIENCE_SETTINGS -> ExperienceSettingsScreen(
                    initialReminderEnabled = reminderSettings.enabled,
                    initialHour = reminderSettings.hour,
                    initialMinute = reminderSettings.minute,
                    initialHapticsEnabled = reminderSettings.hapticsEnabled,
                    initialSoundEnabled = reminderSettings.soundEnabled,
                    initialReducedMotionEnabled = reminderSettings.reducedMotionEnabled,
                    onSave = { enabled, hour, minute, haptics, sound, reducedMotion ->
                        goalViewModel.savePersonalization(
                            enabled,
                            hour,
                            minute,
                            haptics,
                            sound,
                            reducedMotion
                        )
                        if (enabled) requestNotificationPermissionIfNeeded()
                        currentScreen = AppScreen.ACTIVE_SKY
                    },
                    onTestNotification = { testNotification() },
                    onOpenPrivacyPolicy = { openPrivacyPolicy() },
                    onBack = { currentScreen = AppScreen.ACTIVE_SKY }
                )

                AppScreen.REMINDER_SETTINGS -> ReminderSettingsScreen(
                    initialEnabled = reminderSettings.enabled,
                    initialHour = reminderSettings.hour,
                    initialMinute = reminderSettings.minute,
                    onSave = { enabled, hour, minute ->
                        goalViewModel.saveReminderSettings(enabled, hour, minute)
                        if (enabled) requestNotificationPermissionIfNeeded()
                        currentScreen = AppScreen.ACTIVE_SKY
                    },
                    onTestNotification = { testNotification() },
                    onBack = { currentScreen = AppScreen.ACTIVE_SKY }
                )
            }
        }
    }
}

@Composable
private fun WelcomeScreen(
    onCreateGoal: () -> Unit,
    onHowItWorks: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Личный\nнебосвод",
                color = StarWhite,
                fontSize = 42.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 46.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Маленькие шаги становятся звёздами.\nБез серий, обнулений и давления.",
                color = StarMist,
                fontSize = 17.sp,
                lineHeight = 25.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(34.dp))

            PrimaryButton(
                text = "Создать первую цель",
                onClick = onCreateGoal
            )

            Spacer(modifier = Modifier.height(12.dp))

            SecondaryButton(
                text = "Как это работает",
                onClick = onHowItWorks
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Даже туманность — часть пути.",
                color = StarMist,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )
        }

        FooterLabel()
    }
}

@Composable
private fun HowItWorksScreen(
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Как это\nработает",
                color = StarWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            HowItWorksStep(
                title = "1. Выберите путь",
                text = "Одна цель и короткий срок становятся вашим созвездием."
            )

            Spacer(modifier = Modifier.height(18.dp))

            HowItWorksStep(
                title = "2. Зажигайте звёзды",
                text = "Обычная отметка — звезда. Если сделали больше, она светит ярче."
            )

            Spacer(modifier = Modifier.height(18.dp))

            HowItWorksStep(
                title = "3. Не обнуляйте путь",
                text = "Пропуск остаётся мягкой туманностью и не стирает уже пройденное."
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PrimaryButton(text = "Создать первую цель", onClick = onStart)
            Spacer(modifier = Modifier.height(10.dp))
            SecondaryButton(text = "Назад", onClick = onBack)
        }
    }
}

@Composable
private fun HowItWorksStep(
    title: String,
    text: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = StarLavender,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = text,
            color = StarMist,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CreateGoalScreen(
    initialDraft: GoalDraft,
    initialReminderEnabled: Boolean,
    initialReminderHour: Int,
    initialReminderMinute: Int,
    onBack: () -> Unit,
    onPreview: (String, Int, Boolean, Int, Int) -> Unit
) {
    val context = LocalContext.current
    var goalTitle by remember(initialDraft.title) { mutableStateOf(initialDraft.title) }
    var selectedDuration by remember(initialDraft.durationDays) {
        mutableIntStateOf(initialDraft.durationDays)
    }
    var reminderEnabled by remember(initialReminderEnabled) { mutableStateOf(initialReminderEnabled) }
    var reminderHour by remember(initialReminderHour) { mutableIntStateOf(initialReminderHour) }
    var reminderMinute by remember(initialReminderMinute) { mutableIntStateOf(initialReminderMinute) }
    val isGoalValid = goalTitle.trim().length >= 3

    fun openTimePicker() {
        TimePickerDialog(
            context,
            { _, hour, minute ->
                reminderHour = hour
                reminderMinute = minute
            },
            reminderHour,
            reminderMinute,
            DateFormat.is24HourFormat(context)
        ).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(modifier = Modifier.widthIn(max = 420.dp)) {
            Text(
                text = "Начните\nс маленького света",
                color = StarWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 34.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Одна цель. Один срок. Одно созвездие, которое будет расти вместе с вами.",
                color = StarMist,
                fontSize = 15.sp,
                lineHeight = 21.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = goalTitle,
                onValueChange = { if (it.length <= 60) goalTitle = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Ваша цель") },
                placeholder = { Text("Например, читать каждый вечер") },
                supportingText = {
                    Text(
                        text = "От 3 до 60 символов",
                        color = StarMist.copy(alpha = 0.78f),
                        fontSize = 12.sp
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = StarWhite,
                    unfocusedTextColor = StarWhite,
                    focusedContainerColor = FieldBlack,
                    unfocusedContainerColor = FieldBlack,
                    focusedBorderColor = StarLavender,
                    unfocusedBorderColor = StarMist.copy(alpha = 0.45f),
                    focusedLabelColor = StarLavender,
                    unfocusedLabelColor = StarMist,
                    cursorColor = StarLavender,
                    focusedPlaceholderColor = StarMist.copy(alpha = 0.5f),
                    unfocusedPlaceholderColor = StarMist.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(18.dp)
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Длительность экспедиции",
                color = StarWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Срок определит форму созвездия.",
                color = StarMist,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CompactDurationChoice(
                    days = 5,
                    label = "Импульс",
                    selected = selectedDuration == 5,
                    onClick = { selectedDuration = 5 }
                )
                CompactDurationChoice(
                    days = 7,
                    label = "Ритм",
                    selected = selectedDuration == 7,
                    onClick = { selectedDuration = 7 }
                )
                CompactDurationChoice(
                    days = 14,
                    label = "Путь",
                    selected = selectedDuration == 14,
                    onClick = { selectedDuration = 14 }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = durationDescription(selectedDuration),
                color = StarLavender,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            ReminderSetupRow(
                enabled = reminderEnabled,
                hour = reminderHour,
                minute = reminderMinute,
                onEnabledChange = { reminderEnabled = it },
                onTimeClick = { openTimePicker() }
            )

            Spacer(modifier = Modifier.height(14.dp))

            PrimaryButton(
                text = "Выбрать созвездие",
                onClick = {
                    onPreview(
                        goalTitle.trim(),
                        selectedDuration,
                        reminderEnabled,
                        reminderHour,
                        reminderMinute
                    )
                },
                enabled = isGoalValid
            )

            Spacer(modifier = Modifier.height(10.dp))

            SecondaryButton(
                text = "Назад",
                onClick = onBack
            )
        }

        FooterLabel()
    }
}

@Composable
private fun ConstellationPreviewScreen(
    draft: GoalDraft,
    onBack: () -> Unit,
    onStart: () -> Unit
) {
    val template = constellationFor(draft.durationDays)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Ваше созвездие",
                color = StarWhite,
                fontSize = 29.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = draft.title,
                color = StarLavender,
                fontSize = 17.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            ConstellationCanvas(
                template = template,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
            )

            Text(
                text = template.name,
                color = StarWhite,
                fontSize = 23.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${draft.durationDays} дней · ${template.previewDescription}",
                color = StarMist,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            PrimaryButton(
                text = "Начать экспедицию",
                onClick = onStart
            )

            Spacer(modifier = Modifier.height(10.dp))

            SecondaryButton(
                text = "Изменить цель",
                onClick = onBack
            )
        }

        FooterLabel()
    }
}

@Composable
private fun AstronomyGuideScreen(
    durationDays: Int,
    initialToneIndex: Int,
    onBack: () -> Unit
) {
    val selectedTone = AstronomyTone.entries[
        initialToneIndex.coerceIn(0, AstronomyTone.entries.lastIndex)
    ]
    val entry = AstronomyGuide.entryFor(durationDays, selectedTone)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(565.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    text = entry.title,
                    color = StarWhite,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = entry.headline,
                    color = StarLavender,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "${selectedTone.label} · ${selectedTone.subtitle}",
                    color = BrightGold,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(26.dp))

                entry.paragraphs.forEach { paragraph ->
                    Text(
                        text = paragraph,
                        color = StarMist,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(15.dp))
                }

                Text(
                    text = "Для наблюдения",
                    color = BrightGold,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(5.dp))

                Text(
                    text = entry.observationTip,
                    color = StarMist,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = entry.sourcesLabel,
                    color = StarMist.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SecondaryButton(text = "Назад", onClick = onBack)
    }
}

@Composable
private fun SkyOverviewScreen(
    statistics: SkyStatistics,
    onOpenArchive: () -> Unit,
    onOpenSupport: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Небосвод пути",
                color = StarWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Все маленькие огни и туманности складываются в одну вашу карту.",
                color = StarMist,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(30.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ProgressMetric("${statistics.totalStars}", "всего звёзд", StarWhite)
                ProgressMetric("${statistics.totalBrightStars}", "ярких", BrightGold)
                ProgressMetric("${statistics.totalNebulae}", "туманностей", NebulaViolet)
            }

            Spacer(modifier = Modifier.height(34.dp))

            Text(
                text = "Завершено путей · ${statistics.completedPaths}",
                color = StarLavender,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onOpenArchive)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Текущая серия · ${statistics.currentStreak} ${dayWord(statistics.currentStreak)}",
                color = StarMist,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(30.dp))

            Text(
                text = when {
                    statistics.totalStars == 0 -> "Первый огонь уже ждёт своего места на карте."
                    statistics.completedPaths == 0 -> "Каждая отмеченная звезда уже меняет ваш небосвод."
                    else -> "Путь не обнуляется: он становится шире с каждым созвездием."
                },
                color = StarMist,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Поддержать проект",
                color = StarLavender.copy(alpha = 0.84f),
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onOpenSupport)
            )

            Spacer(modifier = Modifier.height(14.dp))

            SecondaryButton(text = "К карте", onClick = onBack)
        }
    }
}

@Composable
private fun SupportProjectScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Поддержать\nпроект",
                color = StarWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Спасибо, что идёте по своему пути вместе с Personal Sky.",
                color = StarMist,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "Приложение всегда останется бесплатным, локальным и без рекламы.",
                color = StarLavender,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 25.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Поддержка будет только добровольной и никогда не откроет скрытые функции. Сейчас здесь нет платежей: способ поддержки появится отдельным и прозрачным шагом после выбора подходящей платформы.",
                color = StarMist,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )
        }

        SecondaryButton(text = "К небосводу", onClick = onBack)
    }
}

private fun dayWord(value: Int): String {
    val remainder100 = value % 100
    val remainder10 = value % 10
    return when {
        remainder100 in 11..14 -> "дней"
        remainder10 == 1 -> "день"
        remainder10 in 2..4 -> "дня"
        else -> "дней"
    }
}

@Composable
private fun GoalManagementScreen(
    goal: StoredGoal,
    onSaveTitle: (String) -> Unit,
    onOpenExperienceSettings: () -> Unit,
    onFinishEarly: () -> Unit,
    onClearAllData: () -> Unit,
    onBack: () -> Unit
) {
    var title by remember(goal.id) { mutableStateOf(goal.title) }
    var showFinishConfirmation by remember { mutableStateOf(false) }
    var showClearAllConfirmation by remember { mutableStateOf(false) }
    val normalizedTitle = title.trim()
    val canSaveTitle = normalizedTitle.length >= 3 && normalizedTitle != goal.title

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(modifier = Modifier.widthIn(max = 420.dp)) {
            Text(
                text = "Управление\nцелью",
                color = StarWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 34.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Меняйте детали пути, не теряя ни одной звезды или туманности.",
                color = StarMist,
                fontSize = 15.sp,
                lineHeight = 21.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { if (it.length <= 60) title = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Название цели") },
                supportingText = {
                    Text(
                        text = "От 3 до 60 символов",
                        color = StarMist.copy(alpha = 0.78f),
                        fontSize = 12.sp
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = StarWhite,
                    unfocusedTextColor = StarWhite,
                    focusedContainerColor = FieldBlack,
                    unfocusedContainerColor = FieldBlack,
                    focusedBorderColor = StarLavender,
                    unfocusedBorderColor = StarMist.copy(alpha = 0.45f),
                    focusedLabelColor = StarLavender,
                    unfocusedLabelColor = StarMist,
                    cursorColor = StarLavender
                ),
                shape = RoundedCornerShape(18.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            PrimaryButton(
                text = "Сохранить название",
                onClick = { onSaveTitle(normalizedTitle) },
                enabled = canSaveTitle
            )

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Ритм пути",
                color = StarWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Напоминания, тактильный отклик и мягкий звук настраиваются на устройстве.",
                color = StarMist,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            SecondaryButton(
                text = "Настроить ритм",
                onClick = onOpenExperienceSettings
            )

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Завершение пути",
                color = StarWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Можно завершить созвездие раньше. Всё, что уже произошло, сохранится в архиве.",
                color = StarMist,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { showFinishConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BrightGold),
                border = BorderStroke(1.dp, BrightGold.copy(alpha = 0.55f))
            ) {
                Text(
                    text = "Завершить путь раньше",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SecondaryButton(text = "К карте", onClick = onBack)

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Локальные данные",
                color = StarWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Цели, отметки и настройки хранятся только на этом устройстве. " +
                        "Действие ниже удаляет их безвозвратно.",
                color = StarMist,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = { showClearAllConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StarMist),
                border = BorderStroke(1.dp, StarMist.copy(alpha = 0.45f))
            ) {
                Text(
                    text = "Удалить все локальные данные",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        FooterLabel()
    }

    if (showClearAllConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirmation = false },
            containerColor = FieldBlack,
            titleContentColor = StarWhite,
            textContentColor = StarMist,
            title = {
                Text(
                    text = "Удалить все данные?",
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Будут удалены цели, отметки, архив и настройки напоминаний. " +
                            "Вернуть их будет нельзя."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAllConfirmation = false
                        onClearAllData()
                    }
                ) {
                    Text("Удалить", color = BrightGold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirmation = false }) {
                    Text("Отмена", color = StarLavender)
                }
            }
        )
    }

    if (showFinishConfirmation) {
        AlertDialog(
            onDismissRequest = { showFinishConfirmation = false },
            containerColor = FieldBlack,
            titleContentColor = StarWhite,
            textContentColor = StarMist,
            title = {
                Text(
                    text = "Завершить созвездие?",
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "Зажжённые звёзды и туманности сохранятся в архиве. Будущие дни не будут считаться пропусками."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinishConfirmation = false
                        onFinishEarly()
                    }
                ) {
                    Text("Завершить", color = BrightGold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinishConfirmation = false }) {
                    Text("Продолжить путь", color = StarLavender)
                }
            }
        )
    }
}

@Composable
private fun ExperienceSettingsScreen(
    initialReminderEnabled: Boolean,
    initialHour: Int,
    initialMinute: Int,
    initialHapticsEnabled: Boolean,
    initialSoundEnabled: Boolean,
    initialReducedMotionEnabled: Boolean,
    onSave: (Boolean, Int, Int, Boolean, Boolean, Boolean) -> Unit,
    onTestNotification: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    var reminderEnabled by remember(initialReminderEnabled) { mutableStateOf(initialReminderEnabled) }
    var hour by remember(initialHour) { mutableIntStateOf(initialHour) }
    var minute by remember(initialMinute) { mutableIntStateOf(initialMinute) }
    var hapticsEnabled by remember(initialHapticsEnabled) { mutableStateOf(initialHapticsEnabled) }
    var soundEnabled by remember(initialSoundEnabled) { mutableStateOf(initialSoundEnabled) }
    var reducedMotionEnabled by remember(initialReducedMotionEnabled) {
        mutableStateOf(initialReducedMotionEnabled)
    }

    fun openTimePicker() {
        TimePickerDialog(
            context,
            { _, selectedHour, selectedMinute ->
                hour = selectedHour
                minute = selectedMinute
            },
            hour,
            minute,
            DateFormat.is24HourFormat(context)
        ).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(modifier = Modifier.widthIn(max = 420.dp)) {
            Text(
                text = "Ритм\nпути",
                color = StarWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 34.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Настройте приглашение вернуться к цели и ощущения от ежедневной отметки.",
                color = StarMist,
                fontSize = 15.sp,
                lineHeight = 21.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Напоминание",
                color = StarWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            ReminderSetupRow(
                enabled = reminderEnabled,
                hour = hour,
                minute = minute,
                onEnabledChange = { reminderEnabled = it },
                onTimeClick = { openTimePicker() }
            )

            if (reminderEnabled) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Проверить уведомление",
                    color = StarLavender,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onTestNotification)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Ощущения",
                color = StarWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(7.dp))

            ExperienceToggleRow(
                title = "Тактильный отклик",
                description = "Короткая мягкая вибрация при зажигании звезды.",
                checked = hapticsEnabled,
                onCheckedChange = { hapticsEnabled = it }
            )

            Spacer(modifier = Modifier.height(10.dp))

            ExperienceToggleRow(
                title = "Тихий сигнал",
                description = "Короткий системный тон после отметки. По умолчанию выключен.",
                checked = soundEnabled,
                onCheckedChange = { soundEnabled = it }
            )

            Spacer(modifier = Modifier.height(10.dp))

            ExperienceToggleRow(
                title = "Меньше движения",
                description = "Убирает мерцание и бегущий след. Небо остаётся спокойным.",
                checked = reducedMotionEnabled,
                onCheckedChange = { reducedMotionEnabled = it }
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Проверить ощущения",
                color = StarLavender,
                fontSize = 13.sp,
                modifier = Modifier.clickable {
                    ExperienceFeedback.celebrateStandard(view, hapticsEnabled, soundEnabled)
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            PrimaryButton(
                text = "Сохранить настройки",
                onClick = {
                    onSave(
                        reminderEnabled,
                        hour,
                        minute,
                        hapticsEnabled,
                        soundEnabled,
                        reducedMotionEnabled
                    )
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            SecondaryButton(text = "К карте", onClick = onBack)

            Spacer(modifier = Modifier.height(20.dp))

            AboutBlock(onOpenPrivacyPolicy = onOpenPrivacyPolicy)
        }

        FooterLabel()
    }
}

@Composable
private fun AboutBlock(onOpenPrivacyPolicy: () -> Unit) {
    Text(
        text = "О приложении",
        color = StarWhite,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )

    Spacer(modifier = Modifier.height(7.dp))

    Text(
        text = "Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        color = StarMist,
        fontSize = 13.sp
    )

    Spacer(modifier = Modifier.height(6.dp))

    Text(
        text = "Политика конфиденциальности",
        color = StarLavender,
        fontSize = 13.sp,
        modifier = Modifier.clickable(onClick = onOpenPrivacyPolicy)
    )

    Spacer(modifier = Modifier.height(6.dp))

    Text(
        text = "Личный небосвод хранит цели, отметки и настройки только на устройстве. " +
                "Без аккаунта, рекламы и облака.",
        color = StarMist,
        fontSize = 12.sp,
        lineHeight = 17.sp
    )
}

@Composable
private fun ExperienceToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.widthIn(max = 250.dp)) {
            Text(
                text = title,
                color = StarWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = StarMist,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AmoledBlack,
                checkedTrackColor = StarLavender,
                uncheckedThumbColor = StarMist,
                uncheckedTrackColor = FieldBlack,
                uncheckedBorderColor = StarMist.copy(alpha = 0.45f)
            )
        )
    }
}

@Composable
private fun ReminderSettingsScreen(
    initialEnabled: Boolean,
    initialHour: Int,
    initialMinute: Int,
    onSave: (Boolean, Int, Int) -> Unit,
    onTestNotification: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var enabled by remember(initialEnabled) { mutableStateOf(initialEnabled) }
    var hour by remember(initialHour) { mutableIntStateOf(initialHour) }
    var minute by remember(initialMinute) { mutableIntStateOf(initialMinute) }

    fun openTimePicker() {
        TimePickerDialog(
            context,
            { _, selectedHour, selectedMinute ->
                hour = selectedHour
                minute = selectedMinute
            },
            hour,
            minute,
            DateFormat.is24HourFormat(context)
        ).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(modifier = Modifier.widthIn(max = 420.dp)) {
            Text(
                text = "Время\nдля созвездия",
                color = StarWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 34.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Напоминание — это приглашение вернуться к своему пути, а не требование.",
                color = StarMist,
                fontSize = 15.sp,
                lineHeight = 21.sp
            )

            Spacer(modifier = Modifier.height(28.dp))

            ReminderSetupRow(
                enabled = enabled,
                hour = hour,
                minute = minute,
                onEnabledChange = { enabled = it },
                onTimeClick = { openTimePicker() }
            )

            Spacer(modifier = Modifier.height(28.dp))

            PrimaryButton(
                text = "Сохранить время",
                onClick = { onSave(enabled, hour, minute) }
            )

            if (enabled) {
                Spacer(modifier = Modifier.height(10.dp))
                SecondaryButton(
                    text = "Проверить уведомление",
                    onClick = onTestNotification
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            SecondaryButton(text = "К карте", onClick = onBack)
        }

        FooterLabel()
    }
}

@Composable
private fun ActiveSkyScreen(
    storedGoal: StoredGoal?,
    fallbackDraft: GoalDraft,
    reminderEnabled: Boolean,
    reminderHour: Int,
    reminderMinute: Int,
    hapticsEnabled: Boolean,
    soundEnabled: Boolean,
    reducedMotionEnabled: Boolean,
    onRecordStandard: () -> Unit,
    onRecordBright: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenExperienceSettings: () -> Unit,
    onOpenGoalSettings: () -> Unit
) {
    val displayTitle = storedGoal?.title ?: fallbackDraft.title
    val displayDuration = storedGoal?.durationDays ?: fallbackDraft.durationDays
    val template = constellationFor(displayDuration)
    val todayIndex = storedGoal?.let { goal ->
        (LocalDate.now().toEpochDay() - goal.startEpochDay)
            .toInt()
            .coerceIn(0, goal.durationDays - 1)
    } ?: 0
    val standardIndexes = storedGoal?.checkIns
        ?.filter { it.type == CheckInType.STANDARD }
        ?.map { it.dayIndex }
        ?.toSet()
        ?: emptySet()
    val brightIndexes = storedGoal?.checkIns
        ?.filter { it.type == CheckInType.BRIGHT }
        ?.map { it.dayIndex }
        ?.toSet()
        ?: emptySet()
    val nebulaIndexes = storedGoal?.checkIns
        ?.filter { it.type == CheckInType.MISSED }
        ?.map { it.dayIndex }
        ?.toSet()
        ?: emptySet()
    val completedIndexes = standardIndexes + brightIndexes
    val isTodayCompleted = todayIndex in completedIndexes
    val isTodayBright = todayIndex in brightIndexes
    val view = LocalView.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = displayTitle,
                color = StarWhite,
                fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = if (reminderEnabled) {
                    "Напоминание · ${formatReminderTime(reminderHour, reminderMinute)}"
                } else {
                    "Напоминание выключено"
                },
                color = StarMist,
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onOpenExperienceSettings)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "${template.name} · день ${todayIndex + 1} из $displayDuration",
                color = StarLavender,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(18.dp))

            ConstellationCanvas(
                template = template,
                litStarIndexes = standardIndexes,
                brightStarIndexes = brightIndexes,
                nebulaStarIndexes = nebulaIndexes,
                currentStarIndex = if (isTodayCompleted) null else todayIndex,
                reducedMotion = reducedMotionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            )

            Text(
                text = when {
                    todayIndex in brightIndexes -> "Сегодня вы сделали больше. Звезда сияет ярче."
                    isTodayCompleted -> "Сегодняшняя звезда уже горит."
                    nebulaIndexes.isNotEmpty() -> "Туманность хранит пропущенный день. Путь продолжается."
                    else -> "Сегодня для созвездия есть место."
                },
                color = StarMist,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            PrimaryButton(
                text = if (isTodayCompleted) "Сегодняшняя звезда горит" else "Зажечь сегодняшнюю звезду",
                onClick = {
                    ExperienceFeedback.celebrateStandard(view, hapticsEnabled, soundEnabled)
                    onRecordStandard()
                },
                enabled = !isTodayCompleted
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (isTodayBright) {
                SecondaryButton(
                    text = "Вернуть обычную звезду",
                    onClick = {
                        ExperienceFeedback.celebrateStandard(view, hapticsEnabled, soundEnabled)
                        onRecordStandard()
                    }
                )
            } else {
                SecondaryButton(
                    text = if (isTodayCompleted) {
                        "Сделал больше — яркая звезда"
                    } else {
                        "Зажечь яркую звезду"
                    },
                    onClick = {
                        ExperienceFeedback.celebrateBright(view, hapticsEnabled, soundEnabled)
                        onRecordBright()
                    }
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            SecondaryButton(
                text = "История созвездия",
                onClick = onOpenHistory
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Управлять целью",
                color = StarLavender,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onOpenGoalSettings)
            )
        }

        FooterLabel()
    }
}

@Composable
private fun ArchiveScreen(
    goals: List<StoredGoal>,
    onBack: () -> Unit,
    onOpenGoal: (StoredGoal) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Архив\nнебосвода",
                color = StarWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (goals.isEmpty()) {
                    "Завершённые созвездия появятся здесь и останутся частью вашей карты."
                } else {
                    "Каждое завершённое созвездие — сохранённый фрагмент вашего пути."
                },
                color = StarMist,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(22.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(390.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (goals.isEmpty()) {
                    item {
                        Text(
                            text = "Пока здесь тихо. Когда первое созвездие завершится, его карта останется с вами.",
                            color = StarMist,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 76.dp)
                        )
                    }
                } else {
                    items(goals, key = { goal -> goal.id }) { goal ->
                        ArchivedGoalRow(goal = goal, onClick = { onOpenGoal(goal) })
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SecondaryButton(text = "К истории текущей цели", onClick = onBack)
        }

        FooterLabel()
    }
}

@Composable
private fun ArchivedGoalRow(
    goal: StoredGoal,
    onClick: () -> Unit
) {
    val template = constellationFor(goal.durationDays)
    val starCount = goal.checkIns.count {
        it.type == CheckInType.STANDARD || it.type == CheckInType.BRIGHT
    }
    val nebulaCount = goal.checkIns.count { it.type == CheckInType.MISSED }

    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = StarWhite),
        border = BorderStroke(1.dp, StarMist.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = goal.title,
                color = StarWhite,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${template.name} · старт ${archiveDate(goal.startEpochDay)}",
                color = StarLavender,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "$starCount звёзд · $nebulaCount туманностей",
                color = StarMist,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun WallpaperExportAction(goal: StoredGoal) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isExporting by remember(goal.id) { mutableStateOf(false) }
    var statusMessage by remember(goal.id) { mutableStateOf<String?>(null) }

    SecondaryButton(
        text = if (isExporting) "Создаём обои…" else "Сохранить как обои",
        onClick = {
            if (!isExporting) {
                isExporting = true
                statusMessage = null
                scope.launch {
                    statusMessage = WallpaperExporter.exportCompletedGoal(context, goal)
                    isExporting = false
                }
            }
        }
    )

    statusMessage?.let { message ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            color = if (message.startsWith("Обои")) StarLavender else BrightGold,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ArchivedDetailScreen(
    goal: StoredGoal,
    reducedMotionEnabled: Boolean,
    onOpenAstronomyGuide: (Int) -> Unit,
    onBack: () -> Unit
) {
    val template = constellationFor(goal.durationDays)
    val standardIndexes = goal.checkIns
        .filter { it.type == CheckInType.STANDARD }
        .map { it.dayIndex }
        .toSet()
    val brightIndexes = goal.checkIns
        .filter { it.type == CheckInType.BRIGHT }
        .map { it.dayIndex }
        .toSet()
    val nebulaIndexes = goal.checkIns
        .filter { it.type == CheckInType.MISSED }
        .map { it.dayIndex }
        .toSet()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Сохранённое\nсозвездие",
                color = StarWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(7.dp))

            Text(
                text = "${goal.title} · ${template.name}",
                color = StarLavender,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            ConstellationCanvas(
                template = template,
                litStarIndexes = standardIndexes,
                brightStarIndexes = brightIndexes,
                nebulaStarIndexes = nebulaIndexes,
                reducedMotion = reducedMotionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(290.dp)
            )

            Text(
                text = "Старт ${archiveDate(goal.startEpochDay)} · ${goal.durationDays} дней пути",
                color = StarMist,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            HistoryLegend()

            Spacer(modifier = Modifier.height(18.dp))

            WallpaperExportAction(goal = goal)

            Spacer(modifier = Modifier.height(10.dp))

            SecondaryButton(
                text = "О созвездии",
                onClick = { onOpenAstronomyGuide(goal.durationDays) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            SecondaryButton(text = "К архиву", onClick = onBack)
        }

        FooterLabel()
    }
}

@Composable
private fun CompletionScreen(
    goal: StoredGoal,
    reducedMotionEnabled: Boolean,
    onOpenAstronomyGuide: () -> Unit,
    onStartNewGoal: () -> Unit
) {
    val template = constellationFor(goal.durationDays)
    val standardIndexes = goal.checkIns
        .filter { it.type == CheckInType.STANDARD }
        .map { it.dayIndex }
        .toSet()
    val brightIndexes = goal.checkIns
        .filter { it.type == CheckInType.BRIGHT }
        .map { it.dayIndex }
        .toSet()
    val nebulaIndexes = goal.checkIns
        .filter { it.type == CheckInType.MISSED }
        .map { it.dayIndex }
        .toSet()
    val brightCount = brightIndexes.size
    val starCount = standardIndexes.size + brightCount

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Созвездие\nзавершено",
                color = StarWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 34.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(7.dp))

            Text(
                text = "${goal.title} · ${template.name}",
                color = StarLavender,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            ConstellationCanvas(
                template = template,
                litStarIndexes = standardIndexes,
                brightStarIndexes = brightIndexes,
                nebulaStarIndexes = nebulaIndexes,
                reducedMotion = reducedMotionEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ProgressMetric(value = "$starCount", label = "звёзд", color = StarWhite)
                ProgressMetric(value = "$brightCount", label = "ярких", color = BrightGold)
                ProgressMetric(value = "${nebulaIndexes.size}", label = "туманностей", color = NebulaViolet)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Спасибо, что шли этот путь. Каждый день вы возвращались к цели — " +
                        "и теперь на вашем небе горит целое созвездие.",
                color = StarWhite,
                fontSize = 15.sp,
                lineHeight = 21.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = when {
                    brightCount > 0 && nebulaIndexes.isNotEmpty() -> {
                        "В вашей карте есть и яркий свет, и туманности. Это целый путь."
                    }
                    brightCount > 0 -> "Вы зажгли особенно яркие звёзды. Пусть этот свет останется с вами."
                    nebulaIndexes.isNotEmpty() -> "Вы дошли до конца. Туманности тоже стали частью вашего неба."
                    else -> "Каждый день стал звездой. Созвездие собрано целиком."
                },
                color = StarMist,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            SecondaryButton(
                text = "Открыть звёздную справку",
                onClick = onOpenAstronomyGuide
            )

            Spacer(modifier = Modifier.height(10.dp))

            WallpaperExportAction(goal = goal)

            Spacer(modifier = Modifier.height(10.dp))

            PrimaryButton(
                text = "Начать новое созвездие",
                onClick = onStartNewGoal
            )
        }

        FooterLabel()
    }
}

@Composable
private fun HistoryScreen(
    goal: StoredGoal,
    onBack: () -> Unit,
    onOpenSkyOverview: () -> Unit,
    onOpenArchive: () -> Unit
) {
    val todayIndex = (LocalDate.now().toEpochDay() - goal.startEpochDay)
        .toInt()
        .coerceIn(0, goal.durationDays - 1)
    val checkInsByDay = goal.checkIns.associateBy { checkIn -> checkIn.dayIndex }
    val brightCount = goal.checkIns.count { it.type == CheckInType.BRIGHT }
    val standardCount = goal.checkIns.count { it.type == CheckInType.STANDARD }
    val nebulaCount = goal.checkIns.count { it.type == CheckInType.MISSED }
    val remainingCount = (goal.durationDays - (todayIndex + 1)).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        BrandLabel()

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "История созвездия",
                color = StarWhite,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = goal.title,
                color = StarLavender,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ProgressMetric(value = "${standardCount + brightCount}", label = "звёзд", color = StarWhite)
                ProgressMetric(value = "$nebulaCount", label = "туманностей", color = NebulaViolet)
                ProgressMetric(value = "$remainingCount", label = "осталось", color = StarMist)
            }

            Spacer(modifier = Modifier.height(26.dp))

            Text(
                text = "Календарь экспедиции",
                color = StarWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            (0 until goal.durationDays).toList().chunked(7).forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    week.forEach { dayIndex ->
                        val dayVisual = when (checkInsByDay[dayIndex]?.type) {
                            CheckInType.STANDARD -> HistoryDayVisual.STANDARD
                            CheckInType.BRIGHT -> HistoryDayVisual.BRIGHT
                            CheckInType.MISSED -> HistoryDayVisual.NEBULA
                            null -> if (dayIndex == todayIndex) HistoryDayVisual.TODAY else HistoryDayVisual.FUTURE
                        }
                        HistoryDayCell(dayIndex = dayIndex, visual = dayVisual)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            Spacer(modifier = Modifier.height(14.dp))

            HistoryLegend()

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (nebulaCount > 0) {
                    "Каждая туманность — часть пути, а не потерянный прогресс."
                } else {
                    "Карта будет постепенно хранить и свет, и туманности вашего пути."
                },
                color = StarMist,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            SecondaryButton(
                text = "Небосвод пути",
                onClick = onOpenSkyOverview
            )

            Spacer(modifier = Modifier.height(10.dp))

            SecondaryButton(
                text = "Архив небосвода",
                onClick = onOpenArchive
            )

            Spacer(modifier = Modifier.height(10.dp))

            SecondaryButton(text = "К карте", onClick = onBack)
        }

        FooterLabel()
    }
}

@Composable
private fun ProgressMetric(value: String, label: String, color: Color) {
    Column(
        modifier = Modifier.width(104.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = color,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = label,
            color = StarMist,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun HistoryDayCell(dayIndex: Int, visual: HistoryDayVisual) {
    val accent = when (visual) {
        HistoryDayVisual.STANDARD -> StarWhite
        HistoryDayVisual.BRIGHT -> BrightGold
        HistoryDayVisual.NEBULA -> NebulaViolet.copy(alpha = 0.5f)
        HistoryDayVisual.TODAY -> StarLavender
        HistoryDayVisual.FUTURE -> StarMist.copy(alpha = 0.45f)
    }
    val background = when (visual) {
        HistoryDayVisual.BRIGHT -> BrightGold.copy(alpha = 0.12f)
        HistoryDayVisual.NEBULA -> NebulaViolet.copy(alpha = 0.05f)
        HistoryDayVisual.TODAY -> StarLavender.copy(alpha = 0.08f)
        else -> FieldBlack
    }

    Surface(
        modifier = Modifier.width(40.dp),
        color = background,
        shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.38f))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${dayIndex + 1}",
                color = StarWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(5.dp))
            Canvas(modifier = Modifier.width(14.dp).height(14.dp)) {
                if (visual == HistoryDayVisual.NEBULA) {
                    drawCircle(NebulaViolet.copy(alpha = 0.08f), radius = 7.dp.toPx())
                }
                if (visual == HistoryDayVisual.BRIGHT) {
                    drawCircle(BrightGold.copy(alpha = 0.14f), radius = 7.dp.toPx())
                }
                drawCircle(
                    color = accent,
                    radius = when (visual) {
                        HistoryDayVisual.BRIGHT -> 3.5.dp.toPx()
                        HistoryDayVisual.NEBULA -> 2.5.dp.toPx()
                        else -> 3.dp.toPx()
                    }
                )
            }
        }
    }
}

@Composable
private fun HistoryLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        LegendItem(color = StarWhite, label = "звезда")
        LegendItem(color = BrightGold, label = "яркая")
        LegendItem(color = NebulaViolet.copy(alpha = 0.55f), label = "туманность")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        modifier = Modifier.width(104.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.width(12.dp).height(12.dp)) {
            drawCircle(color = color, radius = 3.dp.toPx())
        }
        Spacer(modifier = Modifier.width(5.dp))
        Text(text = label, color = StarMist, fontSize = 11.sp)
    }
}

@Composable
private fun ConstellationCanvas(
    template: ConstellationTemplate,
    modifier: Modifier = Modifier,
    litStarIndexes: Set<Int> = emptySet(),
    brightStarIndexes: Set<Int> = emptySet(),
    nebulaStarIndexes: Set<Int> = emptySet(),
    currentStarIndex: Int? = null,
    reducedMotion: Boolean = false
) {
    Canvas(modifier = modifier) {
        val horizontalPadding = 26.dp.toPx()
        val verticalPadding = 22.dp.toPx()
        val availableWidth = size.width - horizontalPadding * 2
        val availableHeight = size.height - verticalPadding * 2

        val points = template.points.map { point ->
            Offset(
                x = horizontalPadding + point.x * availableWidth,
                y = verticalPadding + point.y * availableHeight
            )
        }

        template.edges.forEach { edge ->
            drawLine(
                color = StarLavender.copy(alpha = 0.38f),
                start = points[edge.from],
                end = points[edge.to],
                strokeWidth = 1.25.dp.toPx()
            )
        }

        points.forEachIndexed { index, center ->
            val isBright = index in brightStarIndexes
            val isLit = index in litStarIndexes || isBright
            val isNebula = index in nebulaStarIndexes
            val isCurrent = index == currentStarIndex

            if (isNebula) {
                // Пропуск — это мягкий след в небе, а не самостоятельная награда.
                // Яркость подобрана так, чтобы туманность читалась, но не спорила со звездой.
                drawCircle(
                    color = NebulaViolet.copy(alpha = 0.07f),
                    radius = 32.dp.toPx(),
                    center = center
                )
                drawCircle(
                    color = NebulaViolet.copy(alpha = 0.13f),
                    radius = 18.dp.toPx(),
                    center = center
                )
                drawCircle(
                    color = NebulaViolet.copy(alpha = 0.20f),
                    radius = 9.dp.toPx(),
                    center = center
                )
            }

            if (isBright) {
                // Усиленное золотое свечение и короткий крест-луч для перевыполнения.
                // «Меньше движения» оставляет статичный ореол без лучей.
                drawCircle(
                    color = BrightGold.copy(alpha = 0.07f),
                    radius = 48.dp.toPx(),
                    center = center
                )
                drawCircle(
                    color = BrightGold.copy(alpha = 0.20f),
                    radius = 28.dp.toPx(),
                    center = center
                )
                if (!reducedMotion) {
                    drawLine(
                        color = BrightGold.copy(alpha = 0.42f),
                        start = Offset(center.x - 16.dp.toPx(), center.y),
                        end = Offset(center.x + 16.dp.toPx(), center.y),
                        strokeWidth = 0.8.dp.toPx()
                    )
                    drawLine(
                        color = BrightGold.copy(alpha = 0.42f),
                        start = Offset(center.x, center.y - 16.dp.toPx()),
                        end = Offset(center.x, center.y + 16.dp.toPx()),
                        strokeWidth = 0.8.dp.toPx()
                    )
                }
            } else if (isLit || isCurrent) {
                drawCircle(
                    color = if (isLit) StarLavender.copy(alpha = 0.18f) else StarMist.copy(alpha = 0.11f),
                    radius = if (isLit) 28.dp.toPx() else 20.dp.toPx(),
                    center = center
                )
                if (isCurrent && !reducedMotion) {
                    drawCircle(
                        color = StarLavender.copy(alpha = 0.16f),
                        radius = 13.dp.toPx(),
                        center = center
                    )
                }
            }

            drawCircle(
                color = when {
                    isBright -> BrightGold
                    isLit -> StarWhite
                    isCurrent -> StarLavender
                    isNebula -> NebulaViolet.copy(alpha = 0.55f)
                    else -> StarMist.copy(alpha = 0.5f)
                },
                radius = when {
                    isBright -> 7.dp.toPx()
                    isLit -> 5.dp.toPx()
                    isCurrent -> 4.dp.toPx()
                    isNebula -> 2.5.dp.toPx()
                    else -> 3.dp.toPx()
                },
                center = center
            )

            if (isBright) {
                drawCircle(
                    color = StarWhite.copy(alpha = 0.94f),
                    radius = 3.dp.toPx(),
                    center = center
                )
            }
        }
    }
}

@Composable
private fun ReminderSetupRow(
    enabled: Boolean,
    hour: Int,
    minute: Int,
    onEnabledChange: (Boolean) -> Unit,
    onTimeClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.width(158.dp)) {
            Text(
                text = "Напоминать",
                color = StarWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Одно мягкое приглашение в день",
                color = StarMist,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        if (enabled) {
            OutlinedButton(
                onClick = onTimeClick,
                modifier = Modifier.width(74.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 2.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = StarLavender),
                border = BorderStroke(1.dp, StarLavender.copy(alpha = 0.58f))
            ) {
                Text(
                    text = formatReminderTime(hour, minute),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            Spacer(modifier = Modifier.width(74.dp))
        }

        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AmoledBlack,
                checkedTrackColor = StarLavender,
                uncheckedThumbColor = StarMist,
                uncheckedTrackColor = FieldBlack,
                uncheckedBorderColor = StarMist.copy(alpha = 0.45f)
            )
        )
    }
}

@Composable
private fun CompactDurationChoice(
    days: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.width(92.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(vertical = 9.dp, horizontal = 3.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) StarLavender else FieldBlack,
            contentColor = if (selected) AmoledBlack else StarWhite
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) StarLavender else StarMist.copy(alpha = 0.35f)
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$days",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "дней",
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                color = if (selected) AmoledBlack.copy(alpha = 0.74f) else StarMist
            )
        }
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StarLavender,
            contentColor = AmoledBlack,
            disabledContainerColor = StarMist.copy(alpha = 0.22f),
            disabledContentColor = StarWhite.copy(alpha = 0.38f)
        )
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = StarWhite),
        border = BorderStroke(
            width = 1.dp,
            color = StarMist.copy(alpha = 0.45f)
        )
    ) {
        Text(
            text = text,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun BrandLabel() {
    Text(
        text = "PERSONAL SKY",
        color = StarLavender,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 2.sp
    )
}

@Composable
private fun FooterLabel() {
    Text(
        text = "Локально на вашем устройстве",
        color = StarMist.copy(alpha = 0.65f),
        fontSize = 12.sp
    )
}

private fun archiveDate(epochDay: Long): String = LocalDate.ofEpochDay(epochDay)
    .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ru")))

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

private fun formatReminderTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

private fun durationDescription(days: Int): String = when (days) {
    5 -> "Кассиопея · пять небольших шагов, чтобы почувствовать начало пути."
    7 -> "Большая Медведица · недельный ритм без давления и обнулений."
    else -> "Орион · две недели, чтобы собрать первую заметную историю."
}

private data class ConstellationPoint(val x: Float, val y: Float)
private data class ConstellationEdge(val from: Int, val to: Int)

private data class ConstellationTemplate(
    val name: String,
    val previewDescription: String,
    val points: List<ConstellationPoint>,
    val edges: List<ConstellationEdge>
)

private fun constellationFor(days: Int): ConstellationTemplate = when (days) {
    5 -> ConstellationTemplate(
        name = "Кассиопея",
        previewDescription = "первый мягкий импульс",
        points = listOf(
            ConstellationPoint(0.08f, 0.55f),
            ConstellationPoint(0.28f, 0.30f),
            ConstellationPoint(0.50f, 0.62f),
            ConstellationPoint(0.72f, 0.26f),
            ConstellationPoint(0.92f, 0.52f)
        ),
        edges = listOf(
            ConstellationEdge(0, 1),
            ConstellationEdge(1, 2),
            ConstellationEdge(2, 3),
            ConstellationEdge(3, 4)
        )
    )

    7 -> ConstellationTemplate(
        name = "Большая Медведица",
        previewDescription = "недельный ритм",
        points = listOf(
            ConstellationPoint(0.09f, 0.70f),
            ConstellationPoint(0.25f, 0.58f),
            ConstellationPoint(0.40f, 0.48f),
            ConstellationPoint(0.55f, 0.38f),
            ConstellationPoint(0.70f, 0.28f),
            ConstellationPoint(0.84f, 0.42f),
            ConstellationPoint(0.70f, 0.62f)
        ),
        edges = listOf(
            ConstellationEdge(0, 1),
            ConstellationEdge(1, 2),
            ConstellationEdge(2, 3),
            ConstellationEdge(3, 4),
            ConstellationEdge(4, 5),
            ConstellationEdge(5, 6),
            ConstellationEdge(6, 3)
        )
    )

    else -> ConstellationTemplate(
        name = "Орион",
        previewDescription = "две недели заметного пути",
        points = listOf(
            ConstellationPoint(0.25f, 0.14f),
            ConstellationPoint(0.74f, 0.12f),
            ConstellationPoint(0.34f, 0.33f),
            ConstellationPoint(0.50f, 0.39f),
            ConstellationPoint(0.66f, 0.33f),
            ConstellationPoint(0.38f, 0.49f),
            ConstellationPoint(0.50f, 0.54f),
            ConstellationPoint(0.62f, 0.49f),
            ConstellationPoint(0.42f, 0.64f),
            ConstellationPoint(0.58f, 0.65f),
            ConstellationPoint(0.21f, 0.76f),
            ConstellationPoint(0.77f, 0.76f),
            ConstellationPoint(0.31f, 0.87f),
            ConstellationPoint(0.68f, 0.88f)
        ),
        edges = listOf(
            ConstellationEdge(0, 2),
            ConstellationEdge(2, 3),
            ConstellationEdge(3, 4),
            ConstellationEdge(4, 1),
            ConstellationEdge(2, 5),
            ConstellationEdge(5, 6),
            ConstellationEdge(6, 7),
            ConstellationEdge(7, 4),
            ConstellationEdge(5, 8),
            ConstellationEdge(8, 10),
            ConstellationEdge(6, 9),
            ConstellationEdge(9, 11),
            ConstellationEdge(10, 12),
            ConstellationEdge(11, 13)
        )
    )
}

@Composable
private fun Starfield(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        backgroundStars.forEach { star ->
            drawCircle(
                color = Color.White.copy(alpha = star.alpha),
                radius = star.radiusDp * density,
                center = Offset(
                    x = size.width * star.x,
                    y = size.height * star.y
                )
            )
        }

        drawCircle(
            color = StarLavender.copy(alpha = 0.12f),
            radius = 90.dp.toPx(),
            center = Offset(size.width * 0.76f, size.height * 0.31f)
        )
        drawCircle(
            color = StarLavender.copy(alpha = 0.75f),
            radius = 3.dp.toPx(),
            center = Offset(size.width * 0.76f, size.height * 0.31f)
        )
    }
}

private data class BackgroundStar(
    val x: Float,
    val y: Float,
    val radiusDp: Float,
    val alpha: Float
)

private val backgroundStars = listOf(
    BackgroundStar(0.10f, 0.12f, 1.0f, 0.48f),
    BackgroundStar(0.23f, 0.08f, 1.5f, 0.72f),
    BackgroundStar(0.42f, 0.16f, 1.0f, 0.35f),
    BackgroundStar(0.59f, 0.10f, 1.4f, 0.56f),
    BackgroundStar(0.86f, 0.17f, 1.0f, 0.45f),
    BackgroundStar(0.08f, 0.34f, 1.2f, 0.48f),
    BackgroundStar(0.20f, 0.44f, 1.0f, 0.32f),
    BackgroundStar(0.92f, 0.42f, 1.5f, 0.62f),
    BackgroundStar(0.11f, 0.63f, 1.0f, 0.35f),
    BackgroundStar(0.28f, 0.72f, 1.5f, 0.54f),
    BackgroundStar(0.70f, 0.66f, 1.1f, 0.44f),
    BackgroundStar(0.90f, 0.73f, 1.0f, 0.38f),
    BackgroundStar(0.17f, 0.86f, 1.4f, 0.68f),
    BackgroundStar(0.45f, 0.90f, 1.0f, 0.35f),
    BackgroundStar(0.64f, 0.84f, 1.3f, 0.58f),
    BackgroundStar(0.84f, 0.91f, 1.0f, 0.42f)
)

private val AmoledBlack = Color(0xFF000000)
private val FieldBlack = Color(0xFF0E1018)
private val StarWhite = Color(0xFFF4F7FF)
private val StarMist = Color(0xFFB9C3D8)
private val StarLavender = Color(0xFFB7B8FF)
private val BrightGold = Color(0xFFFFDE8A)
private val NebulaViolet = Color(0xFF9A7BFF)
