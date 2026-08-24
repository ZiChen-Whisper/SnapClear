package com.snapclear.app.ui

internal fun parseScreenshotViewMode(value: String?): ScreenshotViewMode =
    if (value == ScreenshotViewMode.LIST.name) ScreenshotViewMode.LIST else ScreenshotViewMode.CARD

internal fun arePermissionsComplete(
    requiredGranted: Boolean,
    exactAlarmGranted: Boolean,
    batteryOptimizationExempt: Boolean,
    promotedNotificationsGranted: Boolean,
    accessibilityRequired: Boolean,
    accessibilityEnabled: Boolean
): Boolean = requiredGranted && exactAlarmGranted && batteryOptimizationExempt &&
    promotedNotificationsGranted && (!accessibilityRequired ||
    accessibilityEnabled)
