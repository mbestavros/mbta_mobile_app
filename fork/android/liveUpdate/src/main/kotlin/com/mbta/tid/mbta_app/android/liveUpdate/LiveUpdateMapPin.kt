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
     * How many checks in a row may find the pin unable to show before it is reported. The style can
     * take a while to load, for example with a weak connection.
     */
    internal const val REPORT_AFTER_CHECKS = 15

    /**
     * Upstream's pin layer for [stationId], under the fork's layer ID. Null if upstream changed.
     */
    suspend fun pinLayer(stationId: String, colorPalette: ColorPalette): SymbolLayer? {
        val upstream =
            StopLayerGenerator.createStopLayers(
                    colorPalette,
                    StopLayerGenerator.State.StopDetails(stationId),
                )
                .lastOrNull { it.id.endsWith(UpstreamContract.SELECTED_PIN_LAYER_SUFFIX) }
        return upstream?.withId(LAYER_ID)
    }

    /**
     * Why the pin can't be added right now, or null if it can, or is on the map already. Each part
     * is something that upstream's map setup provides, so a reason that lasts is a change there.
     */
    internal fun pinProblem(
        hasMapView: Boolean,
        layerExists: Boolean,
        sourceExists: Boolean,
        anchorExists: Boolean,
    ): String? =
        when {
            !hasMapView -> "no MapView in the view tree"
            layerExists -> null
            !sourceExists -> "no style source ${StopFeaturesBuilder.stopSourceId}"
            !anchorExists -> "no layer ${MapLayerManager.puckAnchorLayerId}"
            else -> null
        }

    /** Shows the pin and keeps it on the map until the calling coroutine is cancelled. */
    suspend fun keepShown(activity: Activity, stationId: String, colorPalette: ColorPalette) {
        val layer =
            pinLayer(stationId, colorPalette)?.toMapbox()
                ?: run {
                    UpstreamContract.report(
                        activity,
                        UpstreamContract.MAP_PIN_LAYER,
                        "no layer ending in ${UpstreamContract.SELECTED_PIN_LAYER_SUFFIX} in " +
                            "StopLayerGenerator",
                    )
                    return
                }
        var addFailure: String? = null
        var problemChecks = 0
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
            val layerExists = map?.styleLayerExists(LAYER_ID) == true
            // Once the pin is shown, this is the only query: the source and anchor matter only to
            // add it.
            val sourceExists =
                !layerExists && map?.styleSourceExists(StopFeaturesBuilder.stopSourceId) == true
            // Before upstream adds its anchor slots, wait: a layer added on top would end up below
            // upstream's stop layers.
            val anchorExists =
                !layerExists && map?.styleLayerExists(MapLayerManager.puckAnchorLayerId) == true
            if (map != null && !layerExists && sourceExists && anchorExists) {
                try {
                    map.addLayerBelow(layer, below = MapLayerManager.puckAnchorLayerId)
                    addFailure = null
                } catch (e: Exception) {
                    // This retries every second, so log a persistent failure only once.
                    if (addFailure == null) {
                        Log.w(LIVE_UPDATE_TAG, "Could not add the destination pin", e)
                    }
                    addFailure = "adding the layer failed: $e"
                }
            }
            val problem =
                if (layerExists) null
                else pinProblem(map != null, layerExists, sourceExists, anchorExists) ?: addFailure
            problemChecks = if (problem == null) 0 else problemChecks + 1
            if (problem != null && problemChecks == REPORT_AFTER_CHECKS) {
                UpstreamContract.report(activity, UpstreamContract.MAP_PIN_NOT_SHOWN, problem)
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
