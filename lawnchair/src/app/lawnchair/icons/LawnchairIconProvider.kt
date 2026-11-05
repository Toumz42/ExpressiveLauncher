package app.lawnchair.icons

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_DATE_CHANGED
import android.content.Intent.ACTION_PACKAGE_ADDED
import android.content.Intent.ACTION_PACKAGE_CHANGED
import android.content.Intent.ACTION_PACKAGE_REMOVED
import android.content.Intent.ACTION_TIMEZONE_CHANGED
import android.content.Intent.ACTION_TIME_CHANGED
import android.content.Intent.ACTION_TIME_TICK
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageItemInfo
import android.content.res.Resources
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import android.util.ArrayMap
import android.util.Log
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toDrawable
import app.lawnchair.data.iconoverride.IconOverrideRepository
import app.lawnchair.icons.iconpack.IconPack
import app.lawnchair.icons.iconpack.IconPackProvider
import app.lawnchair.icons.picker.IconEntry
import app.lawnchair.icons.picker.IconType
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.util.Constants.LAWNICONS_PACKAGE_NAME
import app.lawnchair.util.MultiSafeCloseable
import app.lawnchair.util.isPackageInstalled
import app.lawnchair.util.requireSystemService
import com.android.launcher3.R
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.graphics.ThemeManager
import com.android.launcher3.icons.ClockDrawableWrapper
import com.android.launcher3.icons.LauncherIconProvider
import com.android.launcher3.icons.mono.ThemedIconDrawable
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.SafeCloseable
import javax.inject.Inject
import org.xmlpull.v1.XmlPullParser

@LauncherAppSingleton
class LawnchairIconProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    val themeManager: ThemeManager,
) : LauncherIconProvider(
    context,
    themeManager,
) {
    private val prefs = PreferenceManager.getInstance(context)
    private val themedIconsEnabled get() = prefs.themedIcons.get()

    private val iconPackPref = prefs.iconPackPackage
    private val themedIconSourcePref = prefs.themedIconPackPackage

    private val iconPackProvider = IconPackProvider.INSTANCE.get(context)
    private val overrideRepo = IconOverrideRepository.INSTANCE.get(context)

    private val iconPack
        get() = iconPackProvider.getIconPack(iconPackPref.get())?.apply { loadBlocking() }
    private val themedSourcePackage
        get() = themedIconSourcePackage(iconPackPref.get(), themedIconSourcePref.get())

    private val themeMapCache = ThemedIconMapCache<ThemeData>()

    val themeMap: Map<String, ThemeData>
        get() = themeMapCache.get(themedIconsEnabled, themedSourcePackage, ::loadThemedIconMap)

    private fun resolveIconEntry(componentName: ComponentName, user: UserHandle): IconEntry? {
        val componentKey = ComponentKey(componentName, user)
        // first look for user-overridden icon
        val overrideItem = overrideRepo.overridesMap[componentKey]
        if (overrideItem != null) {
            return overrideItem.toIconEntry()
        }

        // if forced monochrome is enabled, fallback to launcher3 behavior
        if (prefs.forceIconMonochrome.get()) {
            return null
        }

        val iconPack = iconPack ?: return null
        // then look for dynamic calendar
        val calendarEntry = iconPack.getCalendar(componentName)
        if (calendarEntry != null) {
            return calendarEntry
        }
        // finally, look for normal icon
        return iconPack.getIcon(componentName)
    }

    /**
     * Resolves launch component and icon entry.
     * This function tries to resolve the real component so that it works for non-default
     * users (like work / private profiles)
     */
    private fun resolveComponentName(
        info: PackageItemInfo,
        appInfo: ApplicationInfo,
        user: UserHandle,
    ): ComponentName? {
        if (info is ComponentInfo && !info.name.isNullOrEmpty()) {
            return ComponentName(info.packageName ?: appInfo.packageName, info.name)
        }
        return resolveLaunchComponent(appInfo.packageName, user)
    }

    private fun resolveLaunchComponent(packageName: String, user: UserHandle): ComponentName? {
        return try {
            val launcherApps: LauncherApps = context.requireSystemService()
            launcherApps.getActivityList(packageName, user).firstOrNull()?.componentName
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve launch component for $packageName user=$user", t)
            null
        }
    }

    override fun getIcon(
        info: PackageItemInfo,
        appInfo: ApplicationInfo,
        iconDpi: Int,
    ): Drawable {
        val packageName = appInfo.packageName
        val user = UserHandle.getUserHandleForUid(appInfo.uid)
        val componentName = resolveComponentName(info, appInfo, user)

        val explicitOverride = componentName?.let {
            overrideRepo.overridesMap[ComponentKey(it, user)]?.toIconEntry()
        }
        val iconEntry = explicitOverride ?: componentName?.let { resolveIconEntry(it, user) }

        val iconPackEntry = if (iconEntry?.type == IconType.Calendar) {
            iconEntry.resolveDynamicCalendar(getDay())
        } else {
            iconEntry
        }

        val iconPackIcon = iconPackEntry
            ?.takeIf {
                explicitOverride != null || unthemedIconPackPackage(it.packPackageName).isNotEmpty() ||
                    it.packPackageName.isEmpty()
            }
            ?.let { iconPackProvider.getDrawable(it, iconDpi, user) }

        val baseIcon = iconPackIcon ?: super.getIcon(info, appInfo, iconDpi)

        if (!themedIconsEnabled || baseIcon is ClockDrawableWrapper || iconEntry == null) {
            return baseIcon
        }

        // 1. On tente de résoudre l'icône thématique via la méthode dédiée
        resolveThemedIcon(packageName, componentName, iconEntry, iconDpi, explicitOverride, iconPackIcon)?.let {
            return it
        }

        // 2. Fallback géré ici si rien n'a été trouvé par resolveThemedIcon
        val fallbackMonochrome = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) (baseIcon as? AdaptiveIconDrawable)?.monochrome else null
        return if (fallbackMonochrome != null) {
            ThemedIconLayer(baseIcon, fallbackMonochrome)
        } else {
            baseIcon
        }
    }

    private fun resolveThemedIcon(
        packageName: String,
        componentName: ComponentName?,
        iconEntry: IconEntry,
        iconDpi: Int,
        explicitOverride: IconEntry?,
        iconPackIcon: Drawable?,
    ): Drawable? {
        val themedColors = ThemedIconDrawable.getColors(context)
        val clock = iconPackProvider.getClockMetadata(iconEntry)

        val overrideMonochrome = if (explicitOverride?.packPackageName == LAWNICONS_PACKAGE_NAME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (iconPackIcon as? AdaptiveIconDrawable)?.let { it.monochrome ?: it.foreground } ?: iconPackIcon
        } else {
            null
        }

        return when {
            overrideMonochrome != null -> {
                val themedColorsArr = ThemedIconDrawable.getColors(context)
                CustomAdaptiveIconDrawable(
                    themedColorsArr[0].toDrawable(),
                    overrideMonochrome.apply { setTint(themedColorsArr[1]) },
                )
            }

            clock != null -> {
                ClockDrawableWrapper.forPackage(mContext, mClock.packageName, iconDpi)?.getMonochrome()
            }

            packageName == mClock.packageName -> {
                val clockThemedData = ThemeData(context.resources, R.drawable.themed_icon_static_clock)
                CustomAdaptiveIconDrawable(
                    themedColors[0].toDrawable(),
                    clockThemedData.loadPaddedDrawable().apply { setTint(themedColors[1]) },
                )
            }

            packageName == mCalendar.packageName -> {
                val themeData = getThemeDataForPackage(packageName)
                loadCalendarDrawable(iconDpi, themeData)
            }

            else -> {
                val nativeThemed = componentName?.let { ThemedIconCompat.getThemedIcon(context, it) }
                if (nativeThemed != null) {
                    CustomAdaptiveIconDrawable(
                        themedColors[0].toDrawable(),
                        nativeThemed.apply { setTint(themedColors[1]) },
                    )
                } else {
                    val themeData = getThemeDataForPackage(packageName)
                    if (themeData != null) {
                        CustomAdaptiveIconDrawable(
                            themedColors[0].toDrawable(),
                            themeData.loadPaddedDrawable().apply { setTint(themedColors[1]) },
                        )
                    } else {
                        null // Retourne simplement null, le getIcon gérera le fallback
                    }
                }
            }
        }
    }

    override fun getStateForApp(info: ApplicationInfo?): String {
        val base = super.getStateForApp(info)
        val overrideState = if (info != null) {
            val user = UserHandle.getUserHandleForUid(info.uid)
            overrideRepo.getPackageOverrideState(info.packageName, user)
        } else {
            ""
        }
        return "$base|lc:2:" +
            "ip=${iconPackPref.get()}," +
            "tip=${themedIconSourcePref.get()}," +
            "ti=${prefs.themedIcons.get()}," +
            "dti=${prefs.drawerThemedIcons.get()}," +
            "fm=${prefs.forceIconMonochrome.get()}," +
            "ov=$overrideState"
    }

    override fun getThemeDataForPackage(packageName: String?): ThemeData? {
        if (prefs.forceIconMonochrome.get()) {
            return null
        }
        return themeMap[packageName]
    }

    override fun getThemedIconMap(): MutableMap<String, ThemeData> = loadThemedIconMap(themedSourcePackage)

    private fun loadThemedIconMap(sourcePackage: String): MutableMap<String, ThemeData> {
        val themedIconMap = ArrayMap<String, ThemeData>()

        fun ArrayMap<String, ThemeData>.updateFromResources(
            resources: Resources,
            packageName: String,
        ) {
            try {
                @SuppressLint("DiscouragedApi")
                val xmlId = resources.getIdentifier("grayscale_icon_map", "xml", packageName)
                if (xmlId != 0) {
                    val parser = resources.getXml(xmlId)
                    val depth = parser.depth
                    var type: Int
                    while (
                        (
                            parser.next()
                                .also { type = it } != XmlPullParser.END_TAG || parser.depth > depth
                            ) &&
                        type != XmlPullParser.END_DOCUMENT
                    ) {
                        if (type != XmlPullParser.START_TAG) continue
                        if (TAG_ICON == parser.name) {
                            val pkg = parser.getAttributeValue(null, ATTR_PACKAGE)
                            val iconId = parser.getAttributeResourceValue(null, ATTR_DRAWABLE, 0)
                            if (iconId != 0 && pkg.isNotEmpty()) {
                                this[pkg] = ThemeData(resources, iconId)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to parse icon map.", e)
            }
        }

        // first, get Lawnchair's internal grayscale icon map
        themedIconMap.updateFromResources(
            resources = context.resources,
            packageName = context.packageName,
        )

        if (context.packageManager.isPackageInstalled(packageName = sourcePackage)) {
            // get the grayscale icon map of the supported icon pack
            themedIconMap.updateFromResources(
                resources = context.packageManager.getResourcesForApplication(sourcePackage),
                packageName = sourcePackage,
            )
        }

        return themedIconMap
    }

    override fun registerIconChangeListener(
        callback: IconChangeListener,
        handler: Handler,
    ): SafeCloseable {
        return MultiSafeCloseable().apply {
            add(super.registerIconChangeListener(callback, handler))
            add(IconPackChangeReceiver(context, handler, callback))
            add(LawniconsChangeReceiver(context, handler))
        }
    }

    private inner class IconPackChangeReceiver(
        private val context: Context,
        private val handler: Handler,
        private val callback: IconChangeListener,
    ) : SafeCloseable {

        private var calendarAndClockChangeReceiver: CalendarAndClockChangeReceiver? = null
            set(value) {
                field?.close()
                field = value
            }
        private var iconState = themeManager.iconState
        private val iconPackPref = PreferenceManager.getInstance(context).iconPackPackage
        private val themedIconPackPref = PreferenceManager.getInstance(context).themedIconPackPackage

        private val subscription = iconPackPref.subscribeChanges {
            val newState = themeManager.iconState
            if (iconState != newState) {
                iconState = newState
                updateSystemState()
                recreateCalendarAndClockChangeReceiver()
            }
        }
        private val themedIconSubscription = themedIconPackPref.subscribeChanges {
            val newState = themeManager.iconState
            if (iconState != newState) {
                iconState = newState
                updateSystemState()
                recreateCalendarAndClockChangeReceiver()
            }
        }

        init {
            recreateCalendarAndClockChangeReceiver()
        }

        private fun recreateCalendarAndClockChangeReceiver() {
            val iconPack = IconPackProvider.INSTANCE.get(context).getIconPack(iconPackPref.get())
            calendarAndClockChangeReceiver = if (iconPack != null) {
                CalendarAndClockChangeReceiver(context, handler, iconPack, callback)
            } else {
                null
            }
        }

        override fun close() {
            calendarAndClockChangeReceiver = null
            subscription.close()
            themedIconSubscription.close()
        }
    }

    private class CalendarAndClockChangeReceiver(
        private val context: Context,
        handler: Handler,
        private val iconPack: IconPack,
        private val callback: IconChangeListener,
    ) : BroadcastReceiver(),
        SafeCloseable {

        init {
            val filter = IntentFilter(ACTION_TIMEZONE_CHANGED)
            filter.addAction(ACTION_TIME_TICK)
            filter.addAction(ACTION_TIME_CHANGED)
            filter.addAction(ACTION_DATE_CHANGED)
            context.registerReceiver(this, filter, null, handler)
        }

        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TIMEZONE_CHANGED, ACTION_TIME_CHANGED, ACTION_TIME_TICK -> {
                    context.getSystemService<UserManager>()?.userProfiles?.forEach { user ->
                        iconPack.getClocks().forEach { componentName ->
                            callback.onAppIconChanged(
                                componentName.packageName,
                                user,
                            )
                        }
                    }
                }

                ACTION_DATE_CHANGED -> {
                    context.getSystemService<UserManager>()?.userProfiles?.forEach { user ->
                        iconPack.getCalendars().forEach { componentName ->
                            callback.onAppIconChanged(componentName.packageName, user)
                        }
                    }
                }
            }
        }

        override fun close() {
            context.unregisterReceiver(this)
        }
    }

    private inner class LawniconsChangeReceiver(
        private val context: Context,
        handler: Handler,
    ) : BroadcastReceiver(),
        SafeCloseable {

        init {
            val filter = IntentFilter(ACTION_PACKAGE_ADDED)
            filter.addAction(ACTION_PACKAGE_CHANGED)
            filter.addAction(ACTION_PACKAGE_REMOVED)
            filter.addDataScheme("package")
            // The selected source can change after this receiver is registered.
            // Filter the package in onReceive instead of pinning an old source in the filter.
            context.registerReceiver(this, filter, null, handler)
        }

        override fun onReceive(context: Context, intent: Intent) {
            val changedPackage = intent.data?.schemeSpecificPart ?: return
            if (changedPackage == themedSourcePackage || changedPackage == LAWNICONS_PACKAGE_NAME) {
                themeMapCache.clear()
                updateSystemState()
            }
        }

        override fun close() {
            context.unregisterReceiver(this)
        }
    }

    companion object {
        const val TAG = "LawnchairIconProvider"
    }
}
