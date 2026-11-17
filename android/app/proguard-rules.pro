# Keep rules for R8. Libraries ship their own; add app-specific ones here if a release build breaks.

# Glance finds a widget's ids by its class name; R8 merged the three widget classes into one, so every widget drew the Week layout.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
