package com.licht.nuvx.data.settings

import android.content.SharedPreferences
import com.licht.nuvx.ui.components.VideoThumbnails
import kotlinx.coroutines.flow.MutableStateFlow

internal class HomeFeedSettingsController {
    private lateinit var prefs: SharedPreferences

    val hideRecents = MutableStateFlow(false)
    val hideHomeVideos = MutableStateFlow(false)
    val hideTopPicks = MutableStateFlow(false)
    val hideHomeMoods = MutableStateFlow(false)
    val hideTrending = MutableStateFlow(false)
    val squareVideoThumbnails = MutableStateFlow(false)
    val hideExplicit = MutableStateFlow(false)
    val focusedFeed = MutableStateFlow(false)

    fun initialize(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    fun read() {
        hideRecents.value = prefs.getBoolean(KEY_HIDE_RECENTS, false)
        hideHomeVideos.value = prefs.getBoolean(KEY_HIDE_HOME_VIDEOS, false)
        hideTopPicks.value = prefs.getBoolean(KEY_HIDE_TOP_PICKS, false)
        hideHomeMoods.value = prefs.getBoolean(KEY_HIDE_HOME_MOODS, false)
        hideTrending.value = prefs.getBoolean(KEY_HIDE_TRENDING, false)
        squareVideoThumbnails.value = prefs.getBoolean(KEY_SQUARE_VIDEO_THUMBNAILS, false)
        VideoThumbnails.square = squareVideoThumbnails.value
        hideExplicit.value = prefs.getBoolean(KEY_HIDE_EXPLICIT, false)
        focusedFeed.value = prefs.getBoolean(KEY_FOCUSED_FEED, false)
    }

    fun setHideRecents(value: Boolean) {
        hideRecents.value = value
        prefs.edit().putBoolean(KEY_HIDE_RECENTS, value).apply()
    }

    fun setHideHomeVideos(value: Boolean) {
        hideHomeVideos.value = value
        prefs.edit().putBoolean(KEY_HIDE_HOME_VIDEOS, value).apply()
    }

    fun setHideTopPicks(value: Boolean) {
        hideTopPicks.value = value
        prefs.edit().putBoolean(KEY_HIDE_TOP_PICKS, value).apply()
    }

    fun setHideHomeMoods(value: Boolean) {
        hideHomeMoods.value = value
        prefs.edit().putBoolean(KEY_HIDE_HOME_MOODS, value).apply()
    }

    fun setHideTrending(value: Boolean) {
        hideTrending.value = value
        prefs.edit().putBoolean(KEY_HIDE_TRENDING, value).apply()
    }

    fun setHideExplicit(value: Boolean) {
        hideExplicit.value = value
        prefs.edit().putBoolean(KEY_HIDE_EXPLICIT, value).apply()
    }

    fun setFocusedFeed(value: Boolean) {
        focusedFeed.value = value
        prefs.edit().putBoolean(KEY_FOCUSED_FEED, value).apply()
    }

    fun setSquareVideoThumbnails(value: Boolean) {
        squareVideoThumbnails.value = value
        VideoThumbnails.square = value
        prefs.edit().putBoolean(KEY_SQUARE_VIDEO_THUMBNAILS, value).apply()
    }

    private companion object {
        const val KEY_HIDE_RECENTS = "hide_recents"
        const val KEY_HIDE_HOME_VIDEOS = "hide_home_videos"
        const val KEY_HIDE_TOP_PICKS = "hide_top_picks"
        const val KEY_HIDE_HOME_MOODS = "hide_home_moods"
        const val KEY_HIDE_TRENDING = "hide_trending"
        const val KEY_SQUARE_VIDEO_THUMBNAILS = "square_video_thumbnails"
        const val KEY_HIDE_EXPLICIT = "hide_explicit"
        const val KEY_FOCUSED_FEED = "focused_feed"
    }
}
