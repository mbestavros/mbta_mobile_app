package com.mbta.tid.mbta_app.android.liveUpdate

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.mapbox.maps.MapView
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mbta.tid.mbta_app.android.map.MapLayerManager
import com.mbta.tid.mbta_app.android.map.toMapbox
import com.mbta.tid.mbta_app.map.ColorPalette
import com.mbta.tid.mbta_app.map.StopFeaturesBuilder
import com.mbta.tid.mbta_app.map.StopLayerGenerator
import com.mbta.tid.mbta_app.map.style.SymbolLayer
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * A second stop pin on the map, for the destination station on the trip page. Upstream draws only
 * one selected pin, the boarding station.
 *
 * The fork adds its own Mapbox layer on upstream's stop source, without upstream edits. The layer
 * is a copy of upstream's selected-pin layer for the destination, so it looks the same. It sits
 * below the puck anchor slot, above upstream's stop layers. A style reload (such as a dark mode
 * switch) removes custom layers, so [keepShown] checks the layer every second and adds it again.
 * The caller runs it only while MainActivity is started, so it doesn't poll in the background.
 */
internal object LiveUpdateMapPin {
    const val LAYER_ID = "live-update-destination-pin"
    private val CHECK_INTERVAL = 1.seconds

    /**
     * Upstream's pin layer for [stationId], under the fork's layer ID. Null if upstream changed.
     */
    suspend fun pinLayer(stationId: String, colorPalette: ColorPalette): SymbolLayer? {
        val upstream =
            StopLayerGenerator.createStopLayers(
                    colorPalette,
                    StopLayerGenerator.State.StopDetails(stationId),
                )
                .lastOrNull { it.id.endsWith("-selected-pin") }
        if (upstream == null) {
            Log.w(LIVE_UPDATE_TAG, "No selected pin layer in StopLayerGenerator")
            return null
        }
        return upstream.withId(LAYER_ID)
    }

    /** Shows the pin and keeps it on the map until the calling coroutine is cancelled. */
    suspend fun keepShown(activity: Activity, stationId: String, colorPalette: ColorPalette) {
        val layer = pinLayer(stationId, colorPalette)?.toMapbox() ?: return
        var warned = false
        var addWarned = false
        // Walking the view tree every second is wasteful. Search again only if the view is gone.
        var mapView = findMapView(activity.window.decorView)
        // Replace a layer left by a previous station or color palette.
        mapView?.mapboxMap?.let {
            if (it.styleLayerExists(LAYER_ID)) it.removeStyleLayer(LAYER_ID)
        }
        while (coroutineContext.isActive) {
            if (mapView?.isAttachedToWindow != true) {
                mapView = findMapView(activity.window.decorView)
            }
            val map = mapView?.mapboxMap
            if (map == null) {
                if (!warned) Log.w(LIVE_UPDATE_TAG, "No MapView for the destination pin")
                warned = true
            } else if (
                !map.styleLayerExists(LAYER_ID) &&
                    map.styleSourceExists(StopFeaturesBuilder.stopSourceId)
            ) {
                // Before upstream adds its anchor slots, wait: a layer added on top would end up
                // below upstream's stop layers.
                if (map.styleLayerExists(MapLayerManager.puckAnchorLayerId)) {
                    try {
                        map.addLayerBelow(layer, below = MapLayerManager.puckAnchorLayerId)
                    } catch (e: Exception) {
                        // This retries every second, so log a persistent failure only once.
                        if (!addWarned) {
                            Log.w(LIVE_UPDATE_TAG, "Could not add the destination pin", e)
                        }
                        addWarned = true
                    }
                }
            }
            delay(CHECK_INTERVAL)
        }
    }

    fun hide(activity: Activity) {
        val map = findMap(activity) ?: return
        if (map.styleLayerExists(LAYER_ID)) map.removeStyleLayer(LAYER_ID)
    }

    /** Compose's MapboxMap hosts a MapView through AndroidView, so it is in the view tree. */
    private fun findMap(activity: Activity) = findMapView(activity.window.decorView)?.mapboxMap

    private fun findMapView(view: View): MapView? {
        if (view is MapView) return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            findMapView(view.getChildAt(i))?.let {
                return it
            }
        }
        return null
    }

    /** SymbolLayer's style properties are not constructor parameters, so copy() drops them. */
    private fun SymbolLayer.withId(id: String): SymbolLayer {
        val source = this
        return SymbolLayer(id, source.source).apply {
            filter = source.filter
            minZoom = source.minZoom
            iconAllowOverlap = source.iconAllowOverlap
            iconImage = source.iconImage
            iconOffset = source.iconOffset
            iconPadding = source.iconPadding
            iconSize = source.iconSize
            symbolSortKey = source.symbolSortKey
            textAllowOverlap = source.textAllowOverlap
            textColor = source.textColor
            textField = source.textField
            textFont = source.textFont
            textHaloColor = source.textHaloColor
            textHaloWidth = source.textHaloWidth
            textJustify = source.textJustify
            textOffset = source.textOffset
            textOptional = source.textOptional
            textRadialOffset = source.textRadialOffset
            textSize = source.textSize
            textVariableAnchor = source.textVariableAnchor
        }
    }
}
